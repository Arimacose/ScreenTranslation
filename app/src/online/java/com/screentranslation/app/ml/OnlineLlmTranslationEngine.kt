package com.screentranslation.app.ml

import android.content.Context
import com.screentranslation.app.online.OnlineChatClient
import com.screentranslation.app.online.OnlineTranslationConfigRepository
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Online edition backend for user-configured OpenAI-compatible HTTPS APIs. */
class OnlineLlmTranslationEngine(
    context: Context,
    sourceLanguage: String,
    targetLanguage: String,
) : TranslationBackend {
    private val sourceLanguageCode = sourceLanguage.trim().lowercase(Locale.ROOT)
    private val targetLanguageCode = targetLanguage.trim().lowercase(Locale.ROOT)
    private val repository = OnlineTranslationConfigRepository(context.applicationContext)
    private val closed = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<TranslationCall>()
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "online-translation-retry").apply { isDaemon = true }
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10L, TimeUnit.SECONDS)
        .writeTimeout(10L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .callTimeout(40L, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    private var chatClient: OnlineChatClient? = null

    override val inputMode: TranslationInputMode = TranslationInputMode.WHOLE_REGION

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

    @Synchronized
    private fun ensureChatClient(): OnlineChatClient {
        checkOpen()
        chatClient?.let { return it }
        val ready = repository.requireReady()
        return OnlineChatClient(
            callFactory = httpClient,
            retryScheduler = retryScheduler,
            endpoint = ready.endpoint,
            modelId = ready.config.modelId,
            apiKey = ready.apiKey,
            sourceLanguage = sourceLanguageCode,
            targetLanguage = targetLanguageCode,
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
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
