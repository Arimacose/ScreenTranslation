package com.screentranslation.app.ml

import android.content.Context
import android.util.Log
import com.screentranslation.llama.LlamaRuntime
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental multilingual-to-Chinese backend backed by Hy-MT2 1.8B Q4_K_M.
 *
 * The official model is downloaded into app-private no-backup storage and
 * verified before llama.cpp mmaps it. Keeping the model in internal storage
 * avoids ROM-specific scoped-storage/FUSE behavior around Android/data.
 * Model/runtime work is serialized on one executor because a single 2.15
 * GiB-class native context is deliberate here.
 */
class HyMt2Q4TranslationEngine(
    context: Context,
    sourceLanguage: String,
    targetLanguage: String,
) : TranslationBackend {
    private val appContext = context.applicationContext
    private val sourceLanguageCode = sourceLanguage.trim().lowercase(Locale.ROOT)
    private val targetLanguageCode = targetLanguage.trim().lowercase(Locale.ROOT)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hymt2-q4-translation").apply { isDaemon = true }
    }
    private val modelStore = HyMt2Q4ModelStore(appContext, closed)

    @Volatile
    private var runtime: LlamaRuntime? = null

    init {
        require(sourceLanguageCode.isNotBlank()) { "Source language is blank" }
        require(targetLanguageCode == TARGET_LANGUAGE) {
            "Hy-MT2 Q4 experimental currently targets Chinese only"
        }
        require(sourceLanguageCode != targetLanguageCode) {
            "Source language and target language must differ"
        }
    }

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ) {
        submit(onResult) {
            val model = modelStore.ensureModel(onProgress)
            if (warmRuntime) {
                ensureRuntime(model, onProgress)
            }
        }
    }

    override fun translate(text: String, onResult: (Result<String>) -> Unit) {
        if (text.isBlank()) {
            onResult(Result.success(text))
            return
        }
        submit(onResult) {
            val model = modelStore.ensureModel()
            val activeRuntime = ensureRuntime(model)
            val prompt = HyMt2Q4Prompt.build(text)
            HyMt2Q4Prompt.clean(activeRuntime.complete(prompt))
        }
    }

    private fun ensureRuntime(
        model: File,
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): LlamaRuntime {
        checkOpen()
        runtime?.let { return it }
        onProgress(ModelPreparationProgress(ModelPreparationStage.LOADING_RUNTIME))
        return LlamaRuntime().also { created ->
            try {
                val info = created.loadModel(
                    model = model,
                    contextSize = LlamaRuntime.DEFAULT_CONTEXT_SIZE,
                    threads = LlamaRuntime.DEFAULT_THREADS,
                )
                Log.i(TAG, "Hy-MT2 Q4 runtime ready: $info")
                runtime = created
            } catch (error: Throwable) {
                created.close()
                throw error
            }
        }
    }

    private fun <T> submit(
        onResult: (Result<T>) -> Unit,
        operation: () -> T,
    ) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Hy-MT2 Q4 engine is closed")))
            return
        }
        try {
            executor.execute {
                val result = runCatching {
                    checkOpen()
                    operation()
                }
                onResult(result)
            }
        } catch (error: RejectedExecutionException) {
            onResult(Result.failure(error))
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Hy-MT2 Q4 engine is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.execute {
                runtime?.close()
                runtime = null
            }
        } catch (_: RejectedExecutionException) {
            runtime?.close()
            runtime = null
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val TAG = "HyMt2Q4Engine"
        const val TARGET_LANGUAGE = "zh"
    }
}

internal object HyMt2Q4Prompt {
    private val trailingMarkers = listOf(
        "[end of text]",
        "<|endoftext|>",
        "<?hy_end?of?sentence?>",
    )

    fun build(sourceText: String): String =
        "Translate the following text into Chinese. " +
            "Note that you should only output the translated result without any " +
            "additional explanation:\n\n" + sourceText

    fun clean(generation: String): String {
        var cleaned = generation.trim()
        var changed: Boolean
        do {
            changed = false
            trailingMarkers.forEach { marker ->
                if (cleaned.endsWith(marker)) {
                    cleaned = cleaned.removeSuffix(marker).trimEnd()
                    changed = true
                }
            }
        } while (changed)
        return cleaned
    }
}

private class HyMt2Q4ModelStore(
    context: Context,
    private val closed: AtomicBoolean,
) {
    private val modelDirectory = File(context.noBackupFilesDir, "models/hymt2-q4")
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
    private val verifiedMarker = File(modelDirectory, "$MODEL_FILE_NAME.sha256")

    fun ensureModel(
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): File {
        checkOpen()
        modelDirectory.mkdirs()
        check(modelDirectory.isDirectory) { "Failed to create model directory" }

        if (isVerified()) return modelFile

        if (modelFile.isFile && modelFile.length() == MODEL_SIZE_BYTES) {
            verifyAndMark(onProgress)
            return modelFile
        }

        download(onProgress)
        verifyAndMark(onProgress)
        return modelFile
    }

    private fun isVerified(): Boolean =
        modelFile.isFile &&
            modelFile.length() == MODEL_SIZE_BYTES &&
            verifiedMarker.isFile &&
            verifiedMarker.readText(Charsets.UTF_8).trim().equals(
                MODEL_SHA256,
                ignoreCase = true,
            )

    private fun download(onProgress: (ModelPreparationProgress) -> Unit) {
        checkOpen()
        if (modelFile.exists()) {
            check(modelFile.delete()) { "Failed to replace invalid Hy-MT2 model" }
        }
        var existingBytes = partialFile.length().coerceAtMost(MODEL_SIZE_BYTES)
        if (partialFile.length() != existingBytes) {
            check(partialFile.delete()) { "Failed to reset partial Hy-MT2 model" }
            existingBytes = 0L
        }

        val connection = (URI(MODEL_URL).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ScreenTranslation-HyMT2-Q4-Experimental/0.1.0")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (responseCode !in 200..299) {
                error("Hy-MT2 model download HTTP $responseCode")
            }
            if (!append) existingBytes = 0L

            val reportedLength = connection.contentLengthLong
            val totalBytes = when {
                append && reportedLength > 0L -> existingBytes + reportedLength
                reportedLength > 0L -> reportedLength
                else -> MODEL_SIZE_BYTES
            }
            var downloadedBytes = existingBytes
            var lastProgressAt = 0L
            onProgress(
                ModelPreparationProgress(
                    stage = ModelPreparationStage.DOWNLOADING,
                    completedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                ),
            )

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(
                    FileOutputStream(partialFile, append),
                    BUFFER_SIZE,
                ).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkOpen()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NS) {
                            onProgress(
                                ModelPreparationProgress(
                                    stage = ModelPreparationStage.DOWNLOADING,
                                    completedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                            lastProgressAt = now
                        }
                    }
                    output.flush()
                }
            }
            check(partialFile.length() == MODEL_SIZE_BYTES) {
                "Hy-MT2 model size is ${partialFile.length()}, expected $MODEL_SIZE_BYTES"
            }
            try {
                Files.move(
                    partialFile.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    partialFile.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyAndMark(onProgress: (ModelPreparationProgress) -> Unit) {
        onProgress(ModelPreparationProgress(ModelPreparationStage.VERIFYING))
        val actualHash = sha256(modelFile)
        check(actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
            "Hy-MT2 Q4 SHA-256 is $actualHash, expected $MODEL_SHA256"
        }
        verifiedMarker.writeText("$MODEL_SHA256\n", Charsets.UTF_8)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkOpen()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Hy-MT2 Q4 model preparation was cancelled" }
    }

    private companion object {
        const val MODEL_FILE_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
        const val MODEL_SIZE_BYTES = 1_133_080_448L
        const val MODEL_SHA256 =
            "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
        const val MODEL_URL =
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/" +
                "1cd5208700acedef4ef93019b6cfc148b8522d45/" +
                "$MODEL_FILE_NAME?download=true"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 180_000
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_NS = 500_000_000L
    }
}
