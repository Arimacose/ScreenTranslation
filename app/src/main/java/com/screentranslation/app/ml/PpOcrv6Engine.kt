package com.screentranslation.app.ml

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.screentranslation.app.util.TextBlockMerger
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Production PP-OCRv6 small pipeline for multilingual screen text.
 *
 * Gradle pins and hash-checks both official ONNX models. They are copied from
 * APK assets into code cache once so ORT can memory-map file-backed weights.
 * Model materialization, session creation, and inference stay serialized on
 * one worker so service startup never performs model I/O on the main thread.
 * ORT's CPU execution provider owns the four compute threads used inside each
 * invocation. XNNPACK crashed reproducibly during the ORT 1.26 qualification
 * on the target HyperOS build, so runtime upgrades retain the measured CPU
 * configuration until that provider is qualified separately.
 */
internal class PpOcrv6Engine(context: Context) : OcrEngine {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ppocrv6-small").apply { priority = Thread.NORM_PRIORITY }
    }
    private val runtimeCloseLock = Any()

    @Volatile
    private var runtime: RuntimeState? = null

    override fun recognize(
        bitmap: Bitmap,
        onResult: (Result<OcrEngine.Recognition>) -> Unit,
    ) = recognize(bitmap, OcrRequest(OcrProfiles.BALANCED), onResult)

    override fun recognize(
        bitmap: Bitmap,
        request: OcrRequest,
        onResult: (Result<OcrEngine.Recognition>) -> Unit,
    ) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("OCR engine is closed")))
            return
        }

        try {
            executor.execute {
                val result = if (closed.get()) {
                    Result.failure(IllegalStateException("OCR engine is closed"))
                } else {
                    runCatching {
                        val activeRuntime = runtime
                            ?: createRuntime().also { runtime = it }
                        recognizeSynchronously(bitmap, activeRuntime, request.profile)
                    }
                }
                try {
                    onResult(result)
                } finally {
                    if (closed.get()) closeRuntime()
                }
            }
        } catch (error: RejectedExecutionException) {
            onResult(Result.failure(error))
        }
    }

    private fun recognizeSynchronously(
        bitmap: Bitmap,
        runtime: RuntimeState,
        profile: OcrProfile,
    ): OcrEngine.Recognition {
        val detectionInput = prepareDetectionInput(bitmap, profile.detectionLongSide)
        val boxes = OnnxTensor.createTensor(
            runtime.environment,
            FloatBuffer.wrap(detectionInput.values),
            longArrayOf(
                1,
                3,
                detectionInput.height.toLong(),
                detectionInput.width.toLong(),
            ),
        ).use { input ->
            runtime.detectionSession.run(mapOf(INPUT_NAME to input)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = (output.info as TensorInfo).shape
                check(shape.size == 4 && shape[0] == 1L && shape[1] == 1L)
                val mapHeight = shape[2].toInt()
                val mapWidth = shape[3].toInt()
                val probabilities = FloatArray(mapWidth * mapHeight)
                output.floatBuffer.get(probabilities)
                PpOcrv6PostProcessor.extractBoxes(
                    probabilities = probabilities,
                    mapWidth = mapWidth,
                    mapHeight = mapHeight,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                )
            }
        }

        if (boxes.isEmpty()) return OcrEngine.Recognition("", emptyList())

        val crops = boxes.map { box ->
            Bitmap.createBitmap(
                bitmap,
                box.left,
                box.top,
                box.width,
                box.height,
            )
        }
        val recognized = try {
            boxes.zip(recognizeCrops(crops, runtime))
                .mapNotNull { (box, decoded) ->
                    val text = decoded.text.trim()
                    if (decoded.confidence < profile.recognitionThreshold || text.isEmpty()) {
                        null
                    } else {
                        box to decoded.copy(text = text)
                    }
                }
        } finally {
            crops.forEach { crop -> crop.recycleSafely() }
        }

        val lines = recognized.map { it.second.text }

        return OcrEngine.Recognition(
            text = lines.joinToString("\n"),
            blocks = TextBlockMerger.merge(lines),
            regions = recognized.map { (box, decoded) ->
                OcrEngine.TextRegion(
                    text = decoded.text,
                    left = box.left.toFloat() / bitmap.width,
                    top = box.top.toFloat() / bitmap.height,
                    right = box.right.toFloat() / bitmap.width,
                    bottom = box.bottom.toFloat() / bitmap.height,
                    confidence = decoded.confidence,
                )
            },
        )
    }

    private fun recognizeCrops(
        crops: List<Bitmap>,
        runtime: RuntimeState,
    ): List<PpOcrv6CtcDecoder.Decoded> =
        crops.chunked(RECOGNITION_BATCH_SIZE).flatMap { batch ->
            recognizeCropBatch(batch, runtime)
        }

    private fun recognizeCropBatch(
        crops: List<Bitmap>,
        runtime: RuntimeState,
    ): List<PpOcrv6CtcDecoder.Decoded> {
        val widths = crops.map { crop ->
            ceil(RECOGNITION_HEIGHT * crop.width.toDouble() / crop.height)
                .toInt()
                .coerceIn(1, MAX_RECOGNITION_WIDTH)
        }
        val inputWidth = max(BASE_RECOGNITION_WIDTH, widths.max())
        val planeSize = RECOGNITION_HEIGHT * inputWidth
        val values = FloatArray(crops.size * RECOGNITION_CHANNELS * planeSize)

        crops.forEachIndexed { batchIndex, crop ->
            val resizedWidth = widths[batchIndex].coerceAtMost(inputWidth)
            val resized = Bitmap.createScaledBitmap(
                crop,
                resizedWidth,
                RECOGNITION_HEIGHT,
                true,
            )
            try {
                val pixels = IntArray(resizedWidth * RECOGNITION_HEIGHT)
                resized.getPixels(
                    pixels,
                    0,
                    resizedWidth,
                    0,
                    0,
                    resizedWidth,
                    RECOGNITION_HEIGHT,
                )
                val batchOffset =
                    batchIndex * RECOGNITION_CHANNELS * planeSize
                pixels.forEachIndexed { pixelIndex, color ->
                    val row = pixelIndex / resizedWidth
                    val column = pixelIndex - (row * resizedWidth)
                    val destination = row * inputWidth + column
                    values[batchOffset + destination] =
                        normalizeRecognition(color and 0xFF)
                    values[batchOffset + planeSize + destination] =
                        normalizeRecognition((color ushr 8) and 0xFF)
                    values[batchOffset + (planeSize * 2) + destination] =
                        normalizeRecognition((color ushr 16) and 0xFF)
                }
            } finally {
                if (resized !== crop) resized.recycleSafely()
            }
        }

        return OnnxTensor.createTensor(
            runtime.environment,
            FloatBuffer.wrap(values),
            longArrayOf(
                crops.size.toLong(),
                RECOGNITION_CHANNELS.toLong(),
                RECOGNITION_HEIGHT.toLong(),
                inputWidth.toLong(),
            ),
        ).use { input ->
            runtime.recognitionSession.run(mapOf(INPUT_NAME to input)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = (output.info as TensorInfo).shape
                check(shape.size == 3 && shape[0] == crops.size.toLong())
                val timeSteps = shape[1].toInt()
                val classCount = shape[2].toInt()
                check(classCount == runtime.characters.size + 2)
                val buffer = output.floatBuffer
                List(crops.size) { batchIndex ->
                    PpOcrv6CtcDecoder.decode(
                        probabilities = buffer,
                        batchIndex = batchIndex,
                        timeSteps = timeSteps,
                        classCount = classCount,
                        characters = runtime.characters,
                    )
                }
            }
        }
    }

    private fun prepareDetectionInput(bitmap: Bitmap, detectionLongSide: Int): DetectionInput {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        // Screen captures already contain rasterized text at display density.
        // Avoid the generic OCR pipeline's short-side upscale: it multiplies
        // activation memory without helping these screen-text fixtures.
        val ratio = if (
            max(originalWidth, originalHeight) > detectionLongSide
        ) {
            detectionLongSide.toFloat() /
                max(originalWidth, originalHeight)
        } else {
            1f
        }
        val width = max(
            32,
            (originalWidth * ratio / 32f).roundToInt() * 32,
        )
        val height = max(
            32,
            (originalHeight * ratio / 32f).roundToInt() * 32,
        )
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val planeSize = pixels.size
            val values = FloatArray(planeSize * DETECTION_CHANNELS)
            pixels.forEachIndexed { index, color ->
                // PaddleOCR decodes with OpenCV, so the model receives BGR.
                values[index] = normalizeDetection(
                    channel = color and 0xFF,
                    mean = 0.485f,
                    standardDeviation = 0.229f,
                )
                values[planeSize + index] = normalizeDetection(
                    channel = (color ushr 8) and 0xFF,
                    mean = 0.456f,
                    standardDeviation = 0.224f,
                )
                values[(planeSize * 2) + index] = normalizeDetection(
                    channel = (color ushr 16) and 0xFF,
                    mean = 0.406f,
                    standardDeviation = 0.225f,
                )
            }
            DetectionInput(values, width, height)
        } finally {
            if (resized !== bitmap) resized.recycleSafely()
        }
    }

    private fun materializeModel(
        assetPath: String,
        fileName: String,
        expectedBytes: Long,
    ): File = synchronized(MODEL_COPY_LOCK) {
        val directory = appContext.codeCacheDir
            .resolve(MODEL_CACHE_DIRECTORY)
            .apply { mkdirs() }
        val target = directory.resolve(fileName)
        if (target.isFile && target.length() == expectedBytes) return@synchronized target

        val partial = directory.resolve("$fileName.part")
        appContext.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(partial.length() == expectedBytes) {
            "Unexpected model size for $assetPath: ${partial.length()}"
        }
        if (target.exists()) check(target.delete())
        check(partial.renameTo(target)) { "Failed to publish $fileName" }
        target
    }

    private fun createRuntime(): RuntimeState {
        val environment = OrtEnvironment.getEnvironment()
        val sessionOptions = createSessionOptions()
        var detectionSession: OrtSession? = null
        var recognitionSession: OrtSession? = null

        try {
            val detectionModel = materializeModel(
                assetPath = DETECTION_MODEL_ASSET,
                fileName = "det.onnx",
                expectedBytes = DETECTION_MODEL_BYTES,
            )
            val recognitionModel = materializeModel(
                assetPath = RECOGNITION_MODEL_ASSET,
                fileName = "rec.onnx",
                expectedBytes = RECOGNITION_MODEL_BYTES,
            )
            val characters = appContext.assets
                .open(CHARACTERS_ASSET, AssetManager.ACCESS_BUFFER)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines -> lines.toList() }
                .also { check(it.size == CHARACTER_COUNT) }
            detectionSession = environment.createSession(
                detectionModel.absolutePath,
                sessionOptions,
            )
            recognitionSession = environment.createSession(
                recognitionModel.absolutePath,
                sessionOptions,
            )
            return RuntimeState(
                environment = environment,
                sessionOptions = sessionOptions,
                detectionSession = detectionSession,
                recognitionSession = recognitionSession,
                characters = characters,
            )
        } catch (error: Throwable) {
            runCatching { recognitionSession?.close() }
            runCatching { detectionSession?.close() }
            runCatching { sessionOptions.close() }
            throw error
        }
    }

    private fun createSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(CPU_THREADS)
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        val stoppedNormally =
            executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val stoppedAfterInterrupt = if (stoppedNormally) {
            true
        } else {
            executor.shutdownNow()
            executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        if (stoppedAfterInterrupt) closeRuntime()
    }

    private fun closeRuntime() {
        synchronized(runtimeCloseLock) {
            runtime?.close()
            runtime = null
        }
    }

    private fun normalizeDetection(
        channel: Int,
        mean: Float,
        standardDeviation: Float,
    ): Float = ((channel / 255f) - mean) / standardDeviation

    private fun normalizeRecognition(channel: Int): Float =
        ((channel / 255f) - 0.5f) / 0.5f

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private data class DetectionInput(
        val values: FloatArray,
        val width: Int,
        val height: Int,
    )

    private class RuntimeState(
        val environment: OrtEnvironment,
        val sessionOptions: OrtSession.SessionOptions,
        val detectionSession: OrtSession,
        val recognitionSession: OrtSession,
        val characters: List<String>,
    ) : AutoCloseable {
        override fun close() {
            try {
                recognitionSession.close()
            } finally {
                try {
                    detectionSession.close()
                } finally {
                    sessionOptions.close()
                }
            }
        }
    }

    companion object {
        val ENGINE_LABEL: String
            get() =
                "PP-OCRv6 small ONNX + ORT ${OrtEnvironment.getEnvironment().version} " +
                    "CPU4 batch1 det640 arena-off"

        private const val INPUT_NAME = "x"
        private const val DETECTION_MODEL_ASSET = "ppocrv6_small/det.onnx"
        private const val RECOGNITION_MODEL_ASSET = "ppocrv6_small/rec.onnx"
        private const val CHARACTERS_ASSET = "ppocrv6_small/characters.txt"
        private const val MODEL_CACHE_DIRECTORY =
            "ppocrv6-small-28fe5895-b8f84f0b"
        private const val DETECTION_MODEL_BYTES = 9_880_512L
        private const val RECOGNITION_MODEL_BYTES = 21_159_378L
        private const val CHARACTER_COUNT = 18_708
        private const val DETECTION_CHANNELS = 3
        private const val RECOGNITION_CHANNELS = 3
        private const val RECOGNITION_HEIGHT = 48
        private const val BASE_RECOGNITION_WIDTH = 320
        private const val MAX_RECOGNITION_WIDTH = 3_200
        private const val RECOGNITION_BATCH_SIZE = 1
        private const val CPU_THREADS = 4
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private val MODEL_COPY_LOCK = Any()
    }
}

internal data class PpOcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Screen text is axis-aligned, so this proof of concept uses connected
 * components plus DB-style expansion instead of shipping OpenCV/Clipper.
 */
internal object PpOcrv6PostProcessor {
    fun extractBoxes(
        probabilities: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        threshold: Float = 0.2f,
        boxThreshold: Float = 0.45f,
        unclipRatio: Float = 1.4f,
        minimumPixels: Int = 9,
    ): List<PpOcrBox> {
        require(mapWidth > 0 && mapHeight > 0)
        require(probabilities.size == mapWidth * mapHeight)
        val visited = ByteArray(probabilities.size)
        val queue = IntArray(probabilities.size)
        val candidates = mutableListOf<PpOcrBox>()

        probabilities.indices.forEach { start ->
            if (visited[start].toInt() != 0 || probabilities[start] <= threshold) {
                return@forEach
            }

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = 1
            var minX = mapWidth
            var minY = mapHeight
            var maxX = 0
            var maxY = 0
            var pixelCount = 0
            var probabilitySum = 0.0

            while (head < tail) {
                val index = queue[head++]
                val y = index / mapWidth
                val x = index - (y * mapWidth)
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                pixelCount += 1
                probabilitySum += probabilities[index]

                for (nextY in max(0, y - 1)..min(mapHeight - 1, y + 1)) {
                    for (nextX in max(0, x - 1)..min(mapWidth - 1, x + 1)) {
                        val next = (nextY * mapWidth) + nextX
                        if (
                            visited[next].toInt() == 0 &&
                            probabilities[next] > threshold
                        ) {
                            visited[next] = 1
                            queue[tail++] = next
                        }
                    }
                }
            }

            val width = maxX - minX + 1
            val height = maxY - minY + 1
            val score = probabilitySum / max(1, pixelCount)
            if (
                pixelCount < minimumPixels ||
                min(width, height) < 3 ||
                score < boxThreshold
            ) {
                return@forEach
            }

            val area = width.toFloat() * height
            val distance = area * unclipRatio / max(1f, 2f * (width + height))
            val leftOnMap = max(0f, minX - distance)
            val topOnMap = max(0f, minY - distance)
            val rightOnMap = min(mapWidth.toFloat(), maxX + 1 + distance)
            val bottomOnMap = min(mapHeight.toFloat(), maxY + 1 + distance)
            val box = PpOcrBox(
                left = (leftOnMap * sourceWidth / mapWidth).roundToInt()
                    .coerceIn(0, sourceWidth - 1),
                top = (topOnMap * sourceHeight / mapHeight).roundToInt()
                    .coerceIn(0, sourceHeight - 1),
                right = (rightOnMap * sourceWidth / mapWidth).roundToInt()
                    .coerceIn(1, sourceWidth),
                bottom = (bottomOnMap * sourceHeight / mapHeight).roundToInt()
                    .coerceIn(1, sourceHeight),
            )
            if (box.width >= 3 && box.height >= 3) candidates += box
        }

        return mergeLineBoxes(candidates)
    }

    private fun mergeLineBoxes(boxes: List<PpOcrBox>): List<PpOcrBox> {
        val lines = mutableListOf<PpOcrBox>()
        boxes.sortedWith(compareBy(PpOcrBox::top, PpOcrBox::left))
            .forEach { box ->
                var bestIndex = -1
                var bestOverlap = -1f
                lines.forEachIndexed { index, line ->
                    val overlap =
                        max(0, min(box.bottom, line.bottom) - max(box.top, line.top))
                    val minimumHeight = min(box.height, line.height)
                    val overlapRatio = overlap.toFloat() / max(1, minimumHeight)
                    val horizontalGap = max(
                        0,
                        max(box.left, line.left) - min(box.right, line.right),
                    )
                    val maximumGap = 1.75f * max(box.height, line.height)
                    if (
                        overlapRatio >= 0.45f &&
                        horizontalGap <= maximumGap &&
                        overlapRatio > bestOverlap
                    ) {
                        bestIndex = index
                        bestOverlap = overlapRatio
                    }
                }

                if (bestIndex < 0) {
                    lines += box
                } else {
                    val line = lines[bestIndex]
                    lines[bestIndex] = PpOcrBox(
                        left = min(line.left, box.left),
                        top = min(line.top, box.top),
                        right = max(line.right, box.right),
                        bottom = max(line.bottom, box.bottom),
                    )
                }
            }
        return lines.sortedWith(compareBy(PpOcrBox::top, PpOcrBox::left))
    }
}

internal object PpOcrv6CtcDecoder {
    data class Decoded(
        val text: String,
        val confidence: Float,
    )

    fun decode(
        probabilities: FloatBuffer,
        batchIndex: Int,
        timeSteps: Int,
        classCount: Int,
        characters: List<String>,
    ): Decoded {
        require(classCount == characters.size + 2)
        val output = StringBuilder()
        var previous = -1
        var scoreSum = 0f
        var selected = 0
        val batchOffset = batchIndex * timeSteps * classCount

        repeat(timeSteps) { timeStep ->
            val rowOffset = batchOffset + (timeStep * classCount)
            var bestIndex = 0
            var bestScore = probabilities.get(rowOffset)
            for (classIndex in 1 until classCount) {
                val score = probabilities.get(rowOffset + classIndex)
                if (score > bestScore) {
                    bestIndex = classIndex
                    bestScore = score
                }
            }

            if (bestIndex != 0 && bestIndex != previous) {
                output.append(
                    when (bestIndex) {
                        classCount - 1 -> " "
                        else -> characters[bestIndex - 1]
                    },
                )
                scoreSum += bestScore
                selected += 1
            }
            previous = bestIndex
        }

        return Decoded(
            text = output.toString(),
            confidence = if (selected == 0) 0f else scoreSum / selected,
        )
    }
}
