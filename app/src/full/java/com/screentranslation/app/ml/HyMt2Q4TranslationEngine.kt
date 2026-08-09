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
import java.util.concurrent.atomic.AtomicLong

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
    override val profile: TranslationProviderProfile = TranslationProviderProfiles.hyMt2Q4Full

    private val appContext = context.applicationContext
    private val sourceLanguageCode = sourceLanguage.trim().lowercase(Locale.ROOT)
    private val targetLanguageCode = targetLanguage.trim().lowercase(Locale.ROOT)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hymt2-q4-translation").apply { isDaemon = true }
    }
    private val modelStore = HyMt2Q4ModelStore(appContext, closed)

    @Volatile
    private var runtime: HyMt2Q4RuntimeLease? = null

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
    ): TranslationCall {
        submit(onResult) {
            val model = modelStore.ensureModel(onProgress)
            if (warmRuntime) {
                ensureRuntime(model, onProgress)
            }
        }
        return TranslationCall.NONE
    }

    override fun translate(
        text: String,
        onResult: (Result<String>) -> Unit,
    ): TranslationCall {
        if (text.isBlank()) {
            onResult(Result.success(text))
            return TranslationCall.NONE
        }
        submit(onResult) {
            val model = modelStore.ensureModel()
            val activeRuntime = ensureRuntime(model)
            val prompt = HyMt2Q4Prompt.build(text)
            HyMt2Q4Prompt.clean(activeRuntime.complete(prompt))
        }
        return TranslationCall.NONE
    }

    private fun ensureRuntime(
        model: File,
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): HyMt2Q4RuntimeLease {
        checkOpen()
        runtime?.let { return it }
        onProgress(ModelPreparationProgress(ModelPreparationStage.LOADING_RUNTIME))
        return PROCESS_RUNTIME_POOL.acquire(model).also { lease ->
            Log.i(TAG, "Hy-MT2 Q4 runtime ready: ${lease.runtimeInfo}")
            runtime = lease
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

internal interface HyMt2Q4RuntimeHandle : AutoCloseable {
    fun loadModel(model: File): String
    fun complete(prompt: String): String
}

private class LlamaHyMt2Q4RuntimeHandle : HyMt2Q4RuntimeHandle {
    private val delegate = LlamaRuntime()

    override fun loadModel(model: File): String =
        delegate.loadModel(
            model = model,
            contextSize = HyMt2Q4ProviderContract.CONTEXT_WINDOW_TOKENS,
            threads = LlamaRuntime.DEFAULT_THREADS,
        )

    override fun complete(prompt: String): String = delegate.complete(
        prompt = prompt,
        maxTokens = HyMt2Q4ProviderContract.RESERVED_OUTPUT_TOKENS,
    )

    override fun close() = delegate.close()
}

/**
 * Process-wide lease pool for the native runtime.
 *
 * llama.cpp owns one 2 GiB-class model/context in this process. Engines created
 * by the Activity self-test and the capture service therefore share the same
 * runtime and serialize completion calls instead of replacing each other's
 * process-global native state.
 */
internal class HyMt2Q4RuntimePool(
    private val runtimeFactory: () -> HyMt2Q4RuntimeHandle,
) {
    private data class State(
        val generation: Long,
        val modelPath: String,
        val runtime: HyMt2Q4RuntimeHandle,
        val runtimeInfo: String,
        var leaseCount: Int,
    )

    private val generations = AtomicLong(0L)
    private var state: State? = null

    fun acquire(model: File): HyMt2Q4RuntimeLease = synchronized(this) {
        val modelPath = model.canonicalPath
        state?.let { active ->
            check(active.modelPath == modelPath) {
                "A different Hy-MT2 runtime model is already active"
            }
            active.leaseCount += 1
            return@synchronized HyMt2Q4RuntimeLease(
                pool = this,
                generation = active.generation,
                runtimeInfo = active.runtimeInfo,
            )
        }

        val created = runtimeFactory()
        try {
            val info = created.loadModel(model)
            val generation = generations.incrementAndGet()
            state = State(
                generation = generation,
                modelPath = modelPath,
                runtime = created,
                runtimeInfo = info,
                leaseCount = 1,
            )
            HyMt2Q4RuntimeLease(
                pool = this,
                generation = generation,
                runtimeInfo = info,
            )
        } catch (error: Throwable) {
            created.close()
            throw error
        }
    }

    internal fun complete(generation: Long, prompt: String): String =
        synchronized(this) {
            val active = state
            check(active != null && active.generation == generation) {
                "Hy-MT2 Q4 runtime lease is no longer active"
            }
            active.runtime.complete(prompt)
        }

    internal fun release(generation: Long) {
        synchronized(this) {
            val active = state
            if (active == null || active.generation != generation) return
            check(active.leaseCount > 0)
            active.leaseCount -= 1
            if (active.leaseCount == 0) {
                state = null
                active.runtime.close()
            }
        }
    }
}

internal class HyMt2Q4RuntimeLease internal constructor(
    private val pool: HyMt2Q4RuntimePool,
    private val generation: Long,
    val runtimeInfo: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun complete(prompt: String): String {
        check(!closed.get()) { "Hy-MT2 Q4 runtime lease is closed" }
        return pool.complete(generation, prompt)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pool.release(generation)
        }
    }
}

private val PROCESS_RUNTIME_POOL =
    HyMt2Q4RuntimePool(::LlamaHyMt2Q4RuntimeHandle)

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
    private val modelDirectory = File(
        context.noBackupFilesDir,
        HyMt2Q4ModelDescriptor.MODEL_RELATIVE_DIRECTORY,
    )
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
    private val verifiedMarker = File(modelDirectory, "$MODEL_FILE_NAME.sha256")

    fun ensureModel(
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): File {
        checkOpen()
        modelDirectory.mkdirs()
        check(modelDirectory.isDirectory) { "Failed to create model directory" }

        when (classifyCandidate(modelFile, allowResume = false, onProgress)) {
            HyMt2Q4CandidateState.VERIFIED_COMPLETE -> {
                markVerified()
                return modelFile
            }
            HyMt2Q4CandidateState.INVALID -> {
                deleteChecked(modelFile, "invalid Hy-MT2 model")
                deleteChecked(verifiedMarker, "stale Hy-MT2 verification marker")
            }
            HyMt2Q4CandidateState.MISSING,
            HyMt2Q4CandidateState.RESUMABLE,
            -> deleteChecked(verifiedMarker, "stale Hy-MT2 verification marker")
        }

        when (classifyCandidate(partialFile, allowResume = true, onProgress)) {
            HyMt2Q4CandidateState.VERIFIED_COMPLETE -> {
                promotePartial()
                markVerified()
                return modelFile
            }
            HyMt2Q4CandidateState.INVALID ->
                deleteChecked(partialFile, "invalid partial Hy-MT2 model")
            HyMt2Q4CandidateState.MISSING,
            HyMt2Q4CandidateState.RESUMABLE,
            -> Unit
        }

        download(onProgress)
        if (
            classifyCandidate(
                partialFile,
                allowResume = true,
                onProgress = onProgress,
            ) != HyMt2Q4CandidateState.VERIFIED_COMPLETE
        ) {
            deleteChecked(partialFile, "invalid downloaded Hy-MT2 model")
            error("Downloaded Hy-MT2 Q4 model failed size or SHA-256 verification")
        }
        promotePartial()
        markVerified()
        return modelFile
    }

    private fun classifyCandidate(
        file: File,
        allowResume: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
    ): HyMt2Q4CandidateState {
        if (file.isFile && file.length() == MODEL_SIZE_BYTES) {
            onProgress(ModelPreparationProgress(ModelPreparationStage.VERIFYING))
        }
        return classifyHyMt2Q4Candidate(
            file = file,
            expectedSize = MODEL_SIZE_BYTES,
            expectedSha256 = MODEL_SHA256,
            allowResume = allowResume,
            sha256 = ::sha256,
        )
    }

    private fun download(onProgress: (ModelPreparationProgress) -> Unit) {
        checkOpen()
        val existingBytes = partialFile.length()
        check(existingBytes in 0 until MODEL_SIZE_BYTES) {
            "Hy-MT2 partial must be resumable before opening the network"
        }

        val connection = (
            URI(HyMt2Q4ModelDescriptor.MODEL_URL)
                .toURL()
                .openConnection() as HttpURLConnection
            ).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "ScreenTranslation-Full-HY-MT2-Q4-Experimental/0.2.1",
            )
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
            val writeOffset = if (append) existingBytes else 0L

            val reportedLength = connection.contentLengthLong
            val totalBytes = when {
                append && reportedLength > 0L -> writeOffset + reportedLength
                reportedLength > 0L -> reportedLength
                else -> MODEL_SIZE_BYTES
            }
            var downloadedBytes = writeOffset
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
        } finally {
            connection.disconnect()
        }
        check(partialFile.length() == MODEL_SIZE_BYTES) {
            "Hy-MT2 model size is ${partialFile.length()}, expected $MODEL_SIZE_BYTES"
        }
    }

    private fun promotePartial() {
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
    }

    private fun markVerified() {
        verifiedMarker.writeText("$MODEL_SHA256\n", Charsets.UTF_8)
    }

    private fun deleteChecked(file: File, description: String) {
        if (file.exists()) {
            check(file.delete()) { "Failed to delete $description" }
        }
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
        const val MODEL_FILE_NAME = HyMt2Q4ModelDescriptor.MODEL_FILE_NAME
        const val MODEL_SIZE_BYTES = HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES
        const val MODEL_SHA256 = HyMt2Q4ModelDescriptor.MODEL_SHA256
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 180_000
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_NS = 500_000_000L
    }
}

internal enum class HyMt2Q4CandidateState {
    MISSING,
    RESUMABLE,
    VERIFIED_COMPLETE,
    INVALID,
}

internal fun classifyHyMt2Q4Candidate(
    file: File,
    expectedSize: Long,
    expectedSha256: String,
    allowResume: Boolean,
    sha256: (File) -> String,
): HyMt2Q4CandidateState {
    if (!file.isFile) return HyMt2Q4CandidateState.MISSING
    val size = file.length()
    if (size == expectedSize) {
        return if (sha256(file).equals(expectedSha256, ignoreCase = true)) {
            HyMt2Q4CandidateState.VERIFIED_COMPLETE
        } else {
            HyMt2Q4CandidateState.INVALID
        }
    }
    return if (allowResume && size in 0 until expectedSize) {
        HyMt2Q4CandidateState.RESUMABLE
    } else {
        HyMt2Q4CandidateState.INVALID
    }
}

/**
 * Immutable release coordinates used by the Full experimental edition.
 *
 * Keeping the revision, byte count and digest together makes accidental
 * floating-revision downloads visible to both code review and unit tests.
 */
internal object HyMt2Q4ModelDescriptor {
    const val MODEL_REPOSITORY = HyMt2Q4ProviderContract.MODEL_REPOSITORY
    const val MODEL_REVISION = HyMt2Q4ProviderContract.MODEL_REVISION
    const val MODEL_RELATIVE_DIRECTORY = HyMt2Q4ProviderContract.MODEL_RELATIVE_DIRECTORY
    const val MODEL_FILE_NAME = HyMt2Q4ProviderContract.MODEL_FILE_NAME
    const val MODEL_SIZE_BYTES = HyMt2Q4ProviderContract.MODEL_SIZE_BYTES
    const val MODEL_SHA256 = HyMt2Q4ProviderContract.MODEL_SHA256
    const val MODEL_URL =
        "https://huggingface.co/$MODEL_REPOSITORY/resolve/" +
            "$MODEL_REVISION/$MODEL_FILE_NAME?download=true"
}
