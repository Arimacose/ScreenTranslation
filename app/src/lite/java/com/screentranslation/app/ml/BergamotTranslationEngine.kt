package com.screentranslation.app.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Lite-edition translator backed by the pinned Firefox Translations Bergamot
 * ARM64 runtime. One long-lived process owns all models and requests are
 * serialized on one worker to match the deterministic device PoC.
 */
class BergamotTranslationEngine(
    context: Context,
    sourceLanguage: String,
    targetLanguage: String,
) : TranslationBackend {
    private val appContext = context.applicationContext
    private val route = BergamotLanguageRoute.requireSupported(
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
    )
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bergamot-lite-translation").apply { isDaemon = true }
    }
    private val modelStore = BergamotModelStore(appContext, closed)

    @Volatile
    private var runtime: BergamotRunner? = null

    /**
     * Model files are fully hashed once for each engine lifecycle. Translation
     * requests then reuse the verified configs instead of re-reading up to
     * 100 MiB from storage for every clause.
     */
    private var preparedConfigs: List<File>? = null

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ) {
        submit(onResult) {
            if (requireWifi) requireWifiTransport()
            val configs = ensureModels(onProgress)
            if (warmRuntime) ensureRuntime(configs, onProgress)
        }
    }

    override fun translate(text: String, onResult: (Result<String>) -> Unit) {
        if (text.isBlank()) {
            onResult(Result.success(text))
            return
        }
        submit(onResult) {
            val configs = ensureModels()
            ensureRuntime(configs).translate(text)
        }
    }

    private fun ensureModels(
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): List<File> {
        checkOpen()
        preparedConfigs?.let { return it }
        return modelStore.ensureRoute(route, onProgress).also {
            preparedConfigs = it
        }
    }

    private fun requireWifiTransport() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Bergamot model preparation is configured for Wi-Fi only"
        }
    }

    private fun ensureRuntime(
        configs: List<File>,
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): BergamotRunner {
        checkOpen()
        runtime?.let { return it }
        onProgress(ModelPreparationProgress(ModelPreparationStage.LOADING_RUNTIME))
        return BergamotRunner.start(appContext, configs).also { runtime = it }
    }

    private fun <T> submit(
        onResult: (Result<T>) -> Unit,
        operation: () -> T,
    ) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Bergamot engine is closed")))
            return
        }
        try {
            executor.execute {
                onResult(
                    runCatching {
                        checkOpen()
                        operation()
                    },
                )
            }
        } catch (error: RejectedExecutionException) {
            onResult(Result.failure(error))
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Bergamot engine is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtime?.close()
        runtime = null
        preparedConfigs = null
        executor.shutdownNow()
    }
}

internal data class BergamotLanguageRoute(
    val sourceLanguage: String,
    val targetLanguage: String,
    val modelIds: List<String>,
) {
    companion object {
        private val routes = mapOf(
            Pair("en", "zh") to listOf("en-zh"),
            Pair("ja", "zh") to listOf("ja-en", "en-zh"),
        )

        fun requireSupported(
            sourceLanguage: String,
            targetLanguage: String,
        ): BergamotLanguageRoute {
            val source = sourceLanguage.trim().lowercase(Locale.ROOT)
            val target = targetLanguage.trim().lowercase(Locale.ROOT)
            require(source.isNotBlank()) { "Source language is blank" }
            val models = routes[Pair(source, target)]
                ?: throw IllegalArgumentException(
                    "ScreenTranslation Lite / Bergamot currently supports " +
                        "en→zh and ja→en→zh only; selected $source→$target",
                )
            return BergamotLanguageRoute(source, target, models)
        }
    }
}

private class BergamotRunner private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader,
    private val lineReader: BergamotBoundedLineReader,
    private val closed: AtomicBoolean,
) : AutoCloseable {
    private val requestIds = AtomicLong(0L)

    fun translate(source: String): String {
        check(!closed.get()) { "Bergamot runner is closed" }
        val requestId = requestIds.incrementAndGet().toString()
        val payload = Base64.encodeToString(
            source.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        writer.write(requestId)
        writer.write('\t'.code)
        writer.write(payload)
        writer.newLine()
        writer.flush()

        val response = try {
            lineReader.readLine(
                timeoutMillis = RESPONSE_TIMEOUT_MS,
                operation = "Bergamot translation response",
            )
        } catch (error: Exception) {
            close()
            throw error
        } ?: error("Bergamot runner exited with code ${process.exitValueOrNull()}")
        val fields = response.split('\t', limit = 3)
        check(fields.size == 3 && fields[0] == requestId) {
            "Bergamot runner protocol mismatch"
        }
        val decoded = String(Base64.decode(fields[2], Base64.DEFAULT), Charsets.UTF_8)
        check(fields[1] == STATUS_OK) {
            "Bergamot translation failed: $decoded"
        }
        return decoded
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Destroy first so an in-flight readLine() receives EOF. Closing
        // BufferedReader on this thread can otherwise wait forever for the
        // reader lock held by that blocking read.
        runCatching { process.destroy() }
        if (!runCatching { process.waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
        ) {
            runCatching { process.destroyForcibly() }
            runCatching {
                process.waitFor(FORCE_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        lineReader.close()
        closeStreamsAsync(writer, reader)
    }

    companion object {
        private const val RUNNER_FILE_NAME = "libbergamot_runner.so"
        private const val STATUS_OK = "OK"
        private const val READY_PREFIX = "READY\t"
        private const val CLOSE_TIMEOUT_SECONDS = 2L
        private const val FORCE_CLOSE_TIMEOUT_SECONDS = 2L
        private const val READY_TIMEOUT_MS = 60_000L
        private const val RESPONSE_TIMEOUT_MS = 60_000L

        fun start(context: Context, configs: List<File>): BergamotRunner {
            val runnerFile = File(context.applicationInfo.nativeLibraryDir, RUNNER_FILE_NAME)
            check(runnerFile.isFile) { "Bergamot ARM64 runner is missing from the Lite APK" }
            check(runnerFile.canExecute() || runnerFile.setExecutable(true, true)) {
                "Bergamot ARM64 runner is not executable"
            }

            val command = mutableListOf(runnerFile.absolutePath)
            configs.forEach { config ->
                command += "--config"
                command += config.absolutePath
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val closed = AtomicBoolean(false)
            drainStderr(process, closed)
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val lineReader = BergamotBoundedLineReader(
                reader = reader,
                threadName = "bergamot-lite-stdout",
            )
            val runnerInstance = BergamotRunner(process, writer, reader, lineReader, closed)
            try {
                val ready = lineReader.readLine(
                    timeoutMillis = READY_TIMEOUT_MS,
                    operation = "Bergamot runner READY",
                )
                check(ready?.startsWith(READY_PREFIX) == true) {
                    "Bergamot runner did not become ready" +
                        (ready?.let { ": $it" } ?: "")
                }
            } catch (error: Exception) {
                runnerInstance.close()
                throw error
            }
            return runnerInstance
        }

        private fun drainStderr(
            process: Process,
            shutdownRequested: AtomicBoolean,
        ) {
            Thread(
                {
                    val failure = consumeBergamotStderr(process.errorStream) { line ->
                        Log.w("BergamotRunner", line)
                    }
                    if (failure != null) {
                        if (shutdownRequested.get()) {
                            Log.d(
                                "BergamotRunner",
                                "Runner stderr reader closed with the runner",
                            )
                        } else {
                            Log.w(
                                "BergamotRunner",
                                "Runner stderr reader stopped unexpectedly",
                                failure,
                            )
                        }
                    }
                },
                "bergamot-lite-stderr",
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun closeStreamsAsync(
            writer: BufferedWriter,
            reader: BufferedReader,
        ) {
            Thread(
                {
                    runCatching { writer.close() }
                    runCatching { reader.close() }
                },
                "bergamot-lite-stream-cleanup",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }
}

/**
 * Drains runner diagnostics without allowing stream-close races to escape the
 * daemon thread as an uncaught Android process exception.
 */
internal fun consumeBergamotStderr(
    stream: InputStream,
    onLine: (String) -> Unit,
): Exception? =
    try {
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach(onLine)
        }
        null
    } catch (error: Exception) {
        error
    }

/**
 * Keeps blocking BufferedReader operations off the engine/service thread and
 * turns protocol waits into bounded operations. close() intentionally only
 * stops the daemon worker; the runner destroys the child process first and
 * closes the underlying reader asynchronously afterwards.
 */
internal class BergamotBoundedLineReader(
    private val reader: BufferedReader,
    threadName: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    fun readLine(timeoutMillis: Long, operation: String): String? {
        require(timeoutMillis > 0L) { "Timeout must be positive" }
        check(!closed.get()) { "Bergamot stdout reader is closed" }
        val future = try {
            executor.submit<String?> { reader.readLine() }
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Bergamot stdout reader is closed", error)
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException(
                "$operation timed out after $timeoutMillis ms",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }
}

private fun Process.exitValueOrNull(): Int? =
    try {
        exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

private class BergamotModelStore(
    context: Context,
    private val closed: AtomicBoolean,
) {
    private val root = File(context.noBackupFilesDir, "models/bergamot-lite")

    fun ensureRoute(
        route: BergamotLanguageRoute,
        onProgress: (ModelPreparationProgress) -> Unit = {},
    ): List<File> {
        checkOpen()
        val models = route.modelIds.map { id -> MODEL_SPECS.getValue(id) }
        val allFiles = models.flatMap { it.files }
        val totalBytes = allFiles.sumOf { it.compressedSize }
        var completedBytes = 0L
        onProgress(ModelPreparationProgress(ModelPreparationStage.PREPARING))

        val configs = models.map { model ->
            val directory = File(root, model.id)
            check(directory.mkdirs() || directory.isDirectory) {
                "Failed to create Bergamot model directory"
            }
            model.files.forEach { file ->
                ensureFile(
                    model = model,
                    file = file,
                    directory = directory,
                    completedBefore = completedBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
                completedBytes += file.compressedSize
            }
            File(directory, CONFIG_FILE_NAME).also { config ->
                val expected = model.configText
                if (!config.isFile || config.readText(Charsets.UTF_8) != expected) {
                    config.writeText(expected, Charsets.UTF_8)
                }
            }
        }
        return configs
    }

    private fun ensureFile(
        model: BergamotModelSpec,
        file: BergamotFileSpec,
        directory: File,
        completedBefore: Long,
        totalBytes: Long,
        onProgress: (ModelPreparationProgress) -> Unit,
    ) {
        val output = File(directory, file.outputName)
        val marker = File(directory, "${file.outputName}.sha256")
        if (BergamotCachedFileVerifier.isReusable(
                file = output,
                marker = marker,
                expectedSize = file.outputSize,
                expectedSha256 = file.outputSha256,
                checkOpen = ::checkOpen,
            )
        ) {
            return
        }

        val compressed = File(directory, "${file.compressedName}.part")
        download(
            url = "${model.baseUrl}${file.compressedName}",
            destination = compressed,
            expectedSize = file.compressedSize,
            expectedSha256 = file.compressedSha256,
            completedBefore = completedBefore,
            totalBytes = totalBytes,
            onProgress = onProgress,
        )
        onProgress(ModelPreparationProgress(ModelPreparationStage.VERIFYING))
        verifyFile(compressed, file.compressedSize, file.compressedSha256)

        val partialOutput = File(directory, "${file.outputName}.part")
        if (partialOutput.exists()) {
            check(partialOutput.delete()) { "Failed to reset Bergamot output partial" }
        }
        GZIPInputStream(BufferedInputStream(compressed.inputStream(), BUFFER_SIZE)).use { input ->
            BufferedOutputStream(
                FileOutputStream(partialOutput),
                BUFFER_SIZE,
            ).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkOpen()
                    val count = input.read(buffer)
                    if (count < 0) break
                    outputStream.write(buffer, 0, count)
                }
            }
        }
        verifyFile(partialOutput, file.outputSize, file.outputSha256)
        moveReplacing(partialOutput, output)
        marker.writeText("${file.outputSha256}\n", Charsets.UTF_8)
        compressed.delete()
    }

    private fun download(
        url: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        completedBefore: Long,
        totalBytes: Long,
        onProgress: (ModelPreparationProgress) -> Unit,
    ) {
        val decision = BergamotPartialDownloadPlanner.prepare(
            destination = destination,
            expectedSize = expectedSize,
            expectedSha256 = expectedSha256,
            checkOpen = ::checkOpen,
        )
        if (decision.reuseComplete) {
            onProgress(
                ModelPreparationProgress(
                    ModelPreparationStage.DOWNLOADING,
                    completedBefore + expectedSize,
                    totalBytes,
                ),
            )
            return
        }
        var existingBytes = decision.existingBytes
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ScreenTranslation-Lite/0.2.1")
            decision.rangeStart?.let { rangeStart ->
                setRequestProperty("Range", "bytes=$rangeStart-")
            }
        }
        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) {
                "Bergamot model download HTTP $responseCode"
            }
            val append = existingBytes > 0L &&
                responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) existingBytes = 0L
            var downloaded = existingBytes
            onProgress(
                ModelPreparationProgress(
                    ModelPreparationStage.DOWNLOADING,
                    completedBefore + downloaded,
                    totalBytes,
                ),
            )
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(
                    FileOutputStream(destination, append),
                    BUFFER_SIZE,
                ).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastProgressAt = 0L
                    while (true) {
                        checkOpen()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NS) {
                            onProgress(
                                ModelPreparationProgress(
                                    ModelPreparationStage.DOWNLOADING,
                                    completedBefore + downloaded,
                                    totalBytes,
                                ),
                            )
                            lastProgressAt = now
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(destination.length() == expectedSize) {
            "Bergamot download size is ${destination.length()}, expected $expectedSize"
        }
    }

    private fun verifyFile(file: File, expectedSize: Long, expectedHash: String) {
        check(
            BergamotCachedFileVerifier.hashMatches(
                file = file,
                expectedSize = expectedSize,
                expectedSha256 = expectedHash,
                checkOpen = ::checkOpen,
            ),
        ) {
            "Bergamot SHA-256 mismatch for ${file.name}"
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Bergamot model preparation was cancelled" }
    }

    private companion object {
        const val CONFIG_FILE_NAME = "decoder.yml"
        const val BUFFER_SIZE = 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 180_000
        const val PROGRESS_INTERVAL_NS = 500_000_000L

        val MODEL_SPECS = listOf(
            BergamotModelSpec(
                id = "en-zh",
                baseUrl = "https://storage.googleapis.com/" +
                    "moz-fx-translations-data--303e-prod-translations-data/models/" +
                    "en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/",
                files = listOf(
                    BergamotFileSpec(
                        "model.enzh.intgemm.alphas.bin.gz",
                        33_375_922L,
                        "7f255403b3bb2502f08ac4d5ca397a8a5a13f899d2f2e987a4934e089d241d16",
                        "model.enzh.intgemm.alphas.bin",
                        43_849_787L,
                        "4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c",
                    ),
                    BergamotFileSpec(
                        "srcvocab.enzh.spm.gz",
                        407_784L,
                        "7846e3c236388390f4e5d321f8413d67f34c1bab5f066165eeb673bfd07607cc",
                        "srcvocab.enzh.spm",
                        806_952L,
                        "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5",
                    ),
                    BergamotFileSpec(
                        "trgvocab.enzh.spm.gz",
                        425_748L,
                        "4d641ce165b1f8478ee2ffb5149d2d46fab3779dc8fa1e9b97f9af1d2206c091",
                        "trgvocab.enzh.spm",
                        772_004L,
                        "aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d",
                    ),
                    BergamotFileSpec(
                        "lex.50.50.enzh.s2t.bin.gz",
                        2_536_039L,
                        "806f75821c0b838f4a8f4afe5bab3db8289cb7e5187753ba04c3bceadd75687a",
                        "lex.50.50.enzh.s2t.bin",
                        4_485_184L,
                        "8575d8daa10e2dbff316dcdf8e1ce475357bcc2c92bdc63b736a2d5add22f681",
                    ),
                ),
                configText = """
                    relative-paths: true
                    models:
                      - model.enzh.intgemm.alphas.bin
                    vocabs:
                      - srcvocab.enzh.spm
                      - trgvocab.enzh.spm
                    shortlist:
                      - lex.50.50.enzh.s2t.bin
                      - 50
                      - 50
                    beam-size: 4
                    normalize: 1
                    word-penalty: 0
                    mini-batch: 1
                    maxi-batch: 1
                    maxi-batch-sort: src
                    ssplit-mode: sentence
                    max-length-break: 128
                    mini-batch-words: 1024
                    alignment: soft
                    max-length-factor: 2.0
                    gemm-precision: int8Alpha
                """.trimIndent() + "\n",
            ),
            BergamotModelSpec(
                id = "ja-en",
                baseUrl = "https://storage.googleapis.com/" +
                    "moz-fx-translations-data--303e-prod-translations-data/models/" +
                    "ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/",
                files = listOf(
                    BergamotFileSpec(
                        "model.jaen.intgemm.alphas.bin.gz",
                        32_577_435L,
                        "ae56ffbb5556d8e4240b2f208a7c7a2449a4b627ac9d673981ed29eaadaab79d",
                        "model.jaen.intgemm.alphas.bin",
                        43_977_787L,
                        "3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302",
                    ),
                    BergamotFileSpec(
                        "vocab.jaen.spm.gz",
                        746_616L,
                        "12d693f5055525d5cc1e133c8c1b8ed787c77b9bb797400d9a14382ac69c1236",
                        "vocab.jaen.spm",
                        1_443_222L,
                        "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919",
                    ),
                    BergamotFileSpec(
                        "lex.50.50.jaen.s2t.bin.gz",
                        4_819_610L,
                        "438152f5ccd982edb43e88ef51305e3ae7c7b66ee5c20a8fa425e9f1822f9b9b",
                        "lex.50.50.jaen.s2t.bin",
                        9_348_172L,
                        "525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4",
                    ),
                ),
                configText = """
                    relative-paths: true
                    models:
                      - model.jaen.intgemm.alphas.bin
                    vocabs:
                      - vocab.jaen.spm
                      - vocab.jaen.spm
                    shortlist:
                      - lex.50.50.jaen.s2t.bin
                      - 50
                      - 50
                    beam-size: 4
                    normalize: 1
                    word-penalty: 0
                    mini-batch: 1
                    maxi-batch: 1
                    maxi-batch-sort: src
                    ssplit-mode: sentence
                    max-length-break: 128
                    mini-batch-words: 1024
                    alignment: soft
                    max-length-factor: 2.0
                    gemm-precision: int8Alpha
                """.trimIndent() + "\n",
            ),
        ).associateBy { it.id }
    }
}

internal data class BergamotPartialDownloadDecision(
    val reuseComplete: Boolean,
    val existingBytes: Long,
) {
    val rangeStart: Long?
        get() = existingBytes.takeIf { !reuseComplete && it > 0L }
}

internal object BergamotPartialDownloadPlanner {
    fun prepare(
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        checkOpen: () -> Unit = {},
    ): BergamotPartialDownloadDecision {
        require(expectedSize >= 0L) { "Expected size must not be negative" }
        if (!destination.exists()) {
            return BergamotPartialDownloadDecision(
                reuseComplete = false,
                existingBytes = 0L,
            )
        }

        val existingSize = destination.length()
        if (existingSize == expectedSize) {
            if (
                BergamotCachedFileVerifier.hashMatches(
                    file = destination,
                    expectedSize = expectedSize,
                    expectedSha256 = expectedSha256,
                    checkOpen = checkOpen,
                )
            ) {
                return BergamotPartialDownloadDecision(
                    reuseComplete = true,
                    existingBytes = expectedSize,
                )
            }
            reset(destination)
            return BergamotPartialDownloadDecision(
                reuseComplete = false,
                existingBytes = 0L,
            )
        }

        if (existingSize in 1 until expectedSize) {
            return BergamotPartialDownloadDecision(
                reuseComplete = false,
                existingBytes = existingSize,
            )
        }

        if (existingSize > expectedSize) {
            reset(destination)
        }
        return BergamotPartialDownloadDecision(
            reuseComplete = false,
            existingBytes = 0L,
        )
    }

    private fun reset(destination: File) {
        check(destination.delete()) { "Failed to reset Bergamot download partial" }
    }
}

internal object BergamotCachedFileVerifier {
    private const val BUFFER_SIZE = 1024 * 1024

    fun isReusable(
        file: File,
        marker: File,
        expectedSize: Long,
        expectedSha256: String,
        checkOpen: () -> Unit = {},
    ): Boolean {
        if (
            !marker.isFile ||
            !marker.readText(Charsets.UTF_8).trim().equals(expectedSha256, true)
        ) {
            return false
        }
        return hashMatches(file, expectedSize, expectedSha256, checkOpen)
    }

    fun hashMatches(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
        checkOpen: () -> Unit = {},
    ): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
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
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return actual.equals(expectedSha256, true)
    }
}

private data class BergamotModelSpec(
    val id: String,
    val baseUrl: String,
    val files: List<BergamotFileSpec>,
    val configText: String,
)

private data class BergamotFileSpec(
    val compressedName: String,
    val compressedSize: Long,
    val compressedSha256: String,
    val outputName: String,
    val outputSize: Long,
    val outputSha256: String,
)
