package com.screentranslation.app.ml

import android.content.Context
import com.screentranslation.app.online.OnlineChatClient
import com.screentranslation.app.online.OnlineBatchBlock
import com.screentranslation.app.online.OnlineBatchContract
import com.screentranslation.app.online.OnlineBatchCoordinator
import com.screentranslation.app.online.OnlineHttpClientFactory
import com.screentranslation.app.online.OnlineMetricsObserver
import com.screentranslation.app.online.OnlineRequestMetric
import com.screentranslation.app.online.OnlineTranslationConfigRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Online backend for the user's OpenAI-compatible API. */
class OnlineLlmTranslationEngine(
    context: Context,
    sourceLanguage: String,
    targetLanguage: String,
) : TranslationBackend, BatchTranslationBackend {
    override val profile: TranslationProviderProfile = TranslationProviderProfiles.onlineByok

    private val sourceLanguageCode = sourceLanguage.trim().lowercase(Locale.ROOT)
    private val targetLanguageCode = targetLanguage.trim().lowercase(Locale.ROOT)
    private val repository = OnlineTranslationConfigRepository(context.applicationContext)
    private val closed = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<TranslationCall>()
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "online-translation-retry").apply { isDaemon = true }
    }
    private val httpClient = OnlineHttpClientFactory.create()

    @Volatile
    private var chatClient: OnlineChatClient? = null
    @Volatile
    private var metricsObserver: OnlineMetricsObserver = OnlineMetricsObserver.NONE

    internal fun observeMetrics(observer: (OnlineRequestMetric) -> Unit) {
        check(chatClient == null) { "Metrics observer must be set before preparation" }
        metricsObserver = OnlineMetricsObserver(observer)
    }

    override val cacheIdentity: String
        get() = repository.load().cacheIdentity(
            sourceLanguage = sourceLanguageCode,
            targetLanguage = targetLanguageCode,
        )

    init {
        require(sourceLanguageCode.isNotBlank()) { "Source language is blank" }
        require(targetLanguageCode.isNotBlank()) { "Target language is blank" }
        require(sourceLanguageCode != targetLanguageCode) {
            "Source language and target language must differ"
        }
    }

    override fun isPrepared(): Boolean = runCatching {
        checkOpen()
        repository.requireReady()
    }.isSuccess

    override fun currentPreparationIdentity(): String? = runCatching {
        checkOpen()
        val ready = repository.requireReady()
        onlinePreparationIdentity(
            baseUrl = ready.endpoint.baseUrl,
            modelId = ready.modelId,
            consentHost = ready.config.consentHost,
            consentVersion = ready.config.consentVersion,
            apiKey = ready.apiKey,
        )
    }.getOrNull()

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ): TranslationCall {
        onProgress(ModelPreparationProgress(ModelPreparationStage.PREPARING))
        onResult(
            runCatching {
                checkOpen()
                ensureChatClient()
                Unit
            },
        )
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
        val client = try {
            checkOpen()
            ensureChatClient()
        } catch (error: Throwable) {
            onResult(Result.failure(error))
            return TranslationCall.NONE
        }

        val finished = AtomicBoolean(false)
        lateinit var logicalCall: TranslationCall
        logicalCall = try {
            client.translate(
                text = text,
                onFinished = { call ->
                    finished.set(true)
                    activeCalls.remove(call)
                },
                onResult = onResult,
            )
        } catch (error: Throwable) {
            onResult(Result.failure(error))
            return TranslationCall.NONE
        }
        if (!finished.get()) {
            activeCalls += logicalCall
            if (closed.get()) logicalCall.cancel()
        }
        return logicalCall
    }

    override val maximumBatchItems: Int = OnlineBatchContract.MAX_BLOCKS
    override val maximumBatchCharacters: Int = OnlineBatchContract.MAX_CHARACTERS

    override fun translateBatch(
        items: List<TranslationBatchItem>,
        onResult: (Result<Map<String, String>>) -> Unit,
    ): TranslationCall {
        val blocks = items.map { OnlineBatchBlock(it.id, it.text) }
        val client = try {
            checkOpen()
            ensureChatClient()
        } catch (error: Throwable) {
            onResult(Result.failure(error))
            return TranslationCall.NONE
        }
        val coordinator = OnlineBatchCoordinator { part, callback ->
            client.translateBatch(blocks = part, onResult = callback)
        }
        val finished = AtomicBoolean(false)
        lateinit var logicalCall: TranslationCall
        logicalCall = coordinator.translate(blocks) { result ->
            finished.set(true)
            activeCalls.remove(logicalCall)
            onResult(result)
        }
        if (!finished.get()) {
            activeCalls += logicalCall
            if (closed.get()) logicalCall.cancel()
        }
        return logicalCall
    }

    @Synchronized
    private fun ensureChatClient(): OnlineChatClient {
        checkOpen()
        chatClient?.let { return it }
        val ready = repository.requireReady()
        return OnlineChatClient(
            callFactory = httpClient,
            retryScheduler = retryScheduler,
            endpoint = ready.endpoint,
            modelId = ready.modelId,
            apiKey = ready.apiKey,
            sourceLanguage = sourceLanguageCode,
            targetLanguage = targetLanguageCode,
            metricsObserver = metricsObserver,
        ).also { chatClient = it }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Online translation engine is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.toList().forEach(TranslationCall::cancel)
        activeCalls.clear()
        chatClient = null
        retryScheduler.shutdownNow()
        OnlineHttpClientFactory.closeAsync(httpClient)
    }
}

/**
 * Binds a retained Online-ready snapshot to the exact canonical BYOK
 * configuration. The secret itself never appears in the returned identity;
 * only a domain-separated SHA-256 digest of it is included.
 */
internal fun onlinePreparationIdentity(
    baseUrl: String,
    modelId: String,
    consentHost: String,
    consentVersion: Int,
    apiKey: String?,
): String? {
    val presentApiKey = apiKey?.takeIf(String::isNotBlank) ?: return null
    val apiKeyHash = stablePreparationIdentity(
        listOf("online-api-key-v1", presentApiKey),
    )
    return stablePreparationIdentity(
        listOf(
            "online-byok-preparation-v1",
            baseUrl,
            modelId,
            consentHost,
            consentVersion.toString(),
            apiKeyHash,
        ),
    )
}
