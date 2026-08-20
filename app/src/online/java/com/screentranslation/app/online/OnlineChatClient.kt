package com.screentranslation.app.online

import com.screentranslation.app.ml.OnlineByokProviderContract
import com.screentranslation.app.ml.TranslationCall
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val MAX_ONLINE_RESPONSE_BYTES = 1024 * 1024L
private const val DEFAULT_ONLINE_RESPONSE_BUFFER_BYTES = 8 * 1024

internal class OnlineChatClient(
    private val callFactory: Call.Factory,
    private val retryScheduler: ScheduledExecutorService,
    private val endpoint: OpenAiEndpoint,
    private val modelId: String,
    private val apiKey: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val metricsObserver: OnlineMetricsObserver = OnlineMetricsObserver.NONE,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(modelId.isNotBlank()) { "Model ID is blank" }
        require(apiKey.isNotBlank()) { "API key is blank" }
        require('\r' !in apiKey && '\n' !in apiKey) {
            "API key contains invalid characters"
        }
    }

    fun translate(
        text: String,
        onFinished: (LogicalCall) -> Unit = {},
        onResult: (Result<String>) -> Unit,
    ): TranslationCall {
        require(text.length <= MAX_INPUT_CHARACTERS) {
            "OCR text exceeds $MAX_INPUT_CHARACTERS characters"
        }
        if (text.isBlank()) {
            onResult(Result.success(text))
            return TranslationCall.NONE
        }
        return LogicalCall(text, onFinished, onResult).also { it.start() }
    }

    fun translateBatch(
        blocks: List<OnlineBatchBlock>,
        onFinished: (TranslationCall) -> Unit = {},
        onResult: (Result<Map<String, String>>) -> Unit,
    ): TranslationCall {
        OnlineBatchContract.validateRequest(blocks)
        return BatchLogicalCall(blocks, onFinished, onResult).also { it.start() }
    }

    internal inner class LogicalCall(
        private val text: String,
        private val onFinished: (LogicalCall) -> Unit,
        private val onResult: (Result<String>) -> Unit,
    ) : TranslationCall {
        private val cancelled = AtomicBoolean(false)
        private val completed = AtomicBoolean(false)
        private val startedAt = elapsedRealtimeMillis()
        private val requestId = "req-${NEXT_REQUEST_ID.incrementAndGet()}"
        private var attempts = 0
        private var lastStatus: Int? = null

        @Volatile
        private var networkCall: Call? = null

        @Volatile
        private var retryFuture: ScheduledFuture<*>? = null

        fun start() {
            executeAttempt(attemptIndex = 0)
        }

        private fun executeAttempt(attemptIndex: Int) {
            if (cancelled.get() || completed.get()) return
            attempts = attemptIndex + 1
            val request = try {
                buildRequest(text)
            } catch (error: Throwable) {
                finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(error)))
                return
            }
            val call = try {
                callFactory.newCall(request)
            } catch (error: Throwable) {
                finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(error)))
                return
            }
            networkCall = call
            if (cancelled.get()) {
                call.cancel()
                return
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cancelled.get()) {
                        finish(Result.failure(CancellationException("Online request cancelled")))
                        return
                    }
                    val delay = OnlineHttpPolicy.retryDelayForNetwork(
                        error = e,
                        completedAttempts = attemptIndex,
                        fallbackDelayMillis = retryJitterMillis(),
                    )
                    if (delay != null) {
                        scheduleRetry(attemptIndex + 1, delay)
                    } else {
                        finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    var retryDelayMillis: Long? = null
                    response.use {
                        lastStatus = response.code
                        if (cancelled.get()) {
                            finish(Result.failure(CancellationException("Online request cancelled")))
                            return
                        }
                        if (!response.isSuccessful) {
                            retryDelayMillis = OnlineHttpPolicy.retryDelayForStatus(
                                statusCode = response.code,
                                completedAttempts = attemptIndex,
                                retryAfter = response.header("Retry-After"),
                                fallbackDelayMillis = retryJitterMillis(),
                            )
                            if (retryDelayMillis == null) {
                                finish(
                                    Result.failure(
                                        OnlineHttpPolicy.failureForStatus(response.code),
                                    ),
                                )
                            }
                        } else {
                            val translated = runCatching {
                                val body = readOnlineResponseBounded(response.body)
                                OpenAiChatProtocol.parseTranslation(body) to
                                    OpenAiChatProtocol.parseUsage(body)
                            }
                            translated.fold(
                                onSuccess = { (translation, usage) ->
                                    finish(Result.success(translation), usage)
                                },
                                onFailure = {
                                    finish(
                                        Result.failure(
                                            OnlineHttpPolicy.sanitizeNetworkFailure(it),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    // Release the prior response and its connection before a zero-delay
                    // Retry-After can start the next attempt on the scheduler thread.
                    retryDelayMillis?.let { delay ->
                        scheduleRetry(attemptIndex + 1, delay)
                    }
                }
            })
        }

        private fun scheduleRetry(attemptIndex: Int, delayMillis: Long) {
            if (cancelled.get() || completed.get()) return
            retryFuture = retryScheduler.schedule(
                { executeAttempt(attemptIndex) },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            retryFuture?.cancel(false)
            networkCall?.cancel()
            finish(Result.failure(CancellationException("Online request cancelled")))
        }

        private fun finish(
            result: Result<String>,
            usage: Pair<Long?, Long?> = null to null,
        ) {
            if (!completed.compareAndSet(false, true)) return
            retryFuture?.cancel(false)
            runCatching { metricsObserver.onMetric(
                OnlineRequestMetric(
                    requestId = requestId,
                    modelId = modelId,
                    httpStatus = lastStatus,
                    latencyMillis = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L),
                    inputCharacters = text.length,
                    outputCharacters = result.getOrNull()?.length ?: 0,
                    promptTokens = usage.first,
                    completionTokens = usage.second,
                    attempts = attempts.coerceAtLeast(1),
                    outcome = when {
                        cancelled.get() || result.exceptionOrNull() is CancellationException ->
                            OnlineRequestOutcome.CANCELLED
                        result.isSuccess -> OnlineRequestOutcome.SUCCEEDED
                        else -> OnlineRequestOutcome.FAILED
                    },
                ),
            ) }
            onFinished(this)
            onResult(result)
        }
    }

    internal inner class BatchLogicalCall(
        private val blocks: List<OnlineBatchBlock>,
        private val onFinished: (TranslationCall) -> Unit,
        private val onResult: (Result<Map<String, String>>) -> Unit,
    ) : TranslationCall {
        private val cancelled = AtomicBoolean(false)
        private val completed = AtomicBoolean(false)
        private val startedAt = elapsedRealtimeMillis()
        private val requestId = "batch-${NEXT_REQUEST_ID.incrementAndGet()}"
        @Volatile private var networkCall: Call? = null
        @Volatile private var retryFuture: ScheduledFuture<*>? = null
        private var attempts = 0
        private var lastStatus: Int? = null

        fun start() = executeAttempt(0)

        private fun executeAttempt(attemptIndex: Int) {
            if (cancelled.get() || completed.get()) return
            attempts = attemptIndex + 1
            val request = runCatching { buildBatchRequest(blocks) }.getOrElse {
                finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it)))
                return
            }
            val call = runCatching { callFactory.newCall(request) }.getOrElse {
                finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it)))
                return
            }
            networkCall = call
            if (cancelled.get()) {
                call.cancel()
                return
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cancelled.get()) {
                        finish(Result.failure(CancellationException("Online batch cancelled")))
                        return
                    }
                    val delay = OnlineHttpPolicy.retryDelayForNetwork(
                        e, attemptIndex, retryJitterMillis(),
                    )
                    if (delay == null) {
                        finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(e)))
                    } else {
                        scheduleRetry(attemptIndex + 1, delay)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    var retryDelay: Long? = null
                    response.use {
                        lastStatus = response.code
                        if (cancelled.get()) {
                            finish(Result.failure(CancellationException("Online batch cancelled")))
                            return
                        }
                        if (!response.isSuccessful) {
                            retryDelay = OnlineHttpPolicy.retryDelayForStatus(
                                response.code,
                                attemptIndex,
                                response.header("Retry-After"),
                                fallbackDelayMillis = retryJitterMillis(),
                            )
                            if (retryDelay == null) {
                                finish(Result.failure(OnlineHttpPolicy.failureForStatus(response.code)))
                            }
                        } else {
                            val body = runCatching { readOnlineResponseBounded(response.body) }
                                .getOrElse {
                                    finish(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it)))
                                    return
                                }
                            val parsed = runCatching {
                                OpenAiChatProtocol.parseBatchTranslation(body, blocks)
                            }.fold(
                                onSuccess = { Result.success(it) },
                                onFailure = {
                                    Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it))
                                },
                            )
                            finish(parsed, OpenAiChatProtocol.parseUsage(body))
                        }
                    }
                    retryDelay?.let { scheduleRetry(attemptIndex + 1, it) }
                }
            })
        }

        private fun scheduleRetry(attemptIndex: Int, delayMillis: Long) {
            if (cancelled.get() || completed.get()) return
            retryFuture = retryScheduler.schedule(
                { executeAttempt(attemptIndex) }, delayMillis, TimeUnit.MILLISECONDS,
            )
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            retryFuture?.cancel(false)
            networkCall?.cancel()
            finish(Result.failure(CancellationException("Online batch cancelled")))
        }

        private fun finish(
            result: Result<Map<String, String>>,
            usage: Pair<Long?, Long?> = null to null,
        ) {
            if (!completed.compareAndSet(false, true)) return
            retryFuture?.cancel(false)
            runCatching { metricsObserver.onMetric(
                OnlineRequestMetric(
                    requestId, modelId, lastStatus,
                    (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L),
                    blocks.sumOf { it.text.length },
                    result.getOrNull()?.values?.sumOf(String::length) ?: 0,
                    usage.first, usage.second, attempts.coerceAtLeast(1),
                    when {
                        cancelled.get() || result.exceptionOrNull() is CancellationException ->
                            OnlineRequestOutcome.CANCELLED
                        result.isSuccess -> OnlineRequestOutcome.SUCCEEDED
                        else -> OnlineRequestOutcome.FAILED
                    },
                ),
            ) }
            onFinished(this)
            onResult(result)
        }
    }

    private fun buildRequest(text: String): Request {
        val json = OpenAiChatProtocol.buildRequestJson(
            modelId = modelId,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            ocrText = text,
            providerHost = endpoint.host,
        )
        return Request.Builder()
            .url(endpoint.requestUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildBatchRequest(blocks: List<OnlineBatchBlock>): Request {
        val json = OpenAiChatProtocol.buildBatchRequestJson(
            modelId, sourceLanguage, targetLanguage, blocks, endpoint.host,
        )
        return Request.Builder()
            .url(endpoint.requestUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun retryJitterMillis(): Long =
        ThreadLocalRandom.current().nextLong(MIN_RETRY_JITTER_MILLIS, MAX_RETRY_JITTER_MILLIS + 1L)

    internal companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_INPUT_CHARACTERS = OnlineByokProviderContract.MAX_INPUT_CHARACTERS
        const val MIN_RETRY_JITTER_MILLIS = 300L
        const val MAX_RETRY_JITTER_MILLIS = 800L
        private val NEXT_REQUEST_ID = AtomicLong()
    }
}

internal class OnlineModelCatalogClient(
    private val callFactory: Call.Factory,
    private val endpoint: OpenAiEndpoint,
    private val apiKey: String,
) {
    init {
        require(apiKey.isNotBlank()) { "API key is blank" }
        require('\r' !in apiKey && '\n' !in apiKey) { "API key contains invalid characters" }
    }

    fun fetchModels(onResult: (Result<List<OnlineModelDescriptor>>) -> Unit): TranslationCall {
        val request = try {
            Request.Builder()
                .url(endpoint.modelsUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
        } catch (error: Throwable) {
            onResult(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(error)))
            return TranslationCall.NONE
        }
        val call = try {
            callFactory.newCall(request)
        } catch (error: Throwable) {
            onResult(Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(error)))
            return TranslationCall.NONE
        }
        return ModelCatalogCall(call, onResult).also { logicalCall ->
            try {
                call.enqueue(logicalCall)
            } catch (error: Throwable) {
                logicalCall.finish(
                    Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(error)),
                )
            }
        }
    }

    private class ModelCatalogCall(
        private val networkCall: Call,
        private val onResult: (Result<List<OnlineModelDescriptor>>) -> Unit,
    ) : Callback, TranslationCall {
        private val cancelled = AtomicBoolean(false)
        private val completed = AtomicBoolean(false)

        override fun onFailure(call: Call, e: IOException) {
            finish(
                Result.failure(
                    if (cancelled.get()) {
                        CancellationException("Model catalog request cancelled")
                    } else {
                        OnlineHttpPolicy.sanitizeNetworkFailure(e)
                    },
                ),
            )
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (cancelled.get()) {
                    finish(Result.failure(CancellationException("Model catalog request cancelled")))
                    return
                }
                if (!response.isSuccessful) {
                    finish(Result.failure(OnlineHttpPolicy.failureForStatus(response.code)))
                    return
                }
                finish(
                    runCatching {
                        OpenAiChatProtocol.parseModels(
                            readOnlineResponseBounded(response.body),
                        )
                    }.fold(
                        onSuccess = { Result.success(it) },
                        onFailure = {
                            Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it))
                        },
                    ),
                )
            }
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            networkCall.cancel()
            finish(Result.failure(CancellationException("Model catalog request cancelled")))
        }

        fun finish(result: Result<List<OnlineModelDescriptor>>) {
            if (!completed.compareAndSet(false, true)) return
            onResult(result)
        }
    }
}

private fun readOnlineResponseBounded(body: ResponseBody): String {
    val declaredSize = body.contentLength()
    if (declaredSize > MAX_ONLINE_RESPONSE_BYTES) {
        throw OnlineTranslationException(OnlineFailureCategory.RESPONSE)
    }
    return body.byteStream().use { input ->
        val output = ByteArrayOutputStream(
            declaredSize.takeIf { it in 1..MAX_ONLINE_RESPONSE_BYTES }
                ?.toInt()
                ?: DEFAULT_ONLINE_RESPONSE_BUFFER_BYTES,
        )
        val buffer = ByteArray(DEFAULT_ONLINE_RESPONSE_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ONLINE_RESPONSE_BYTES) {
                throw OnlineTranslationException(OnlineFailureCategory.RESPONSE)
            }
            output.write(buffer, 0, count)
        }
        String(output.toByteArray(), Charsets.UTF_8)
    }
}
