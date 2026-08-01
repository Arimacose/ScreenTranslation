package com.screentranslation.app.online

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

internal class OnlineChatClient(
    private val callFactory: Call.Factory,
    private val retryScheduler: ScheduledExecutorService,
    private val endpoint: OpenAiEndpoint,
    private val modelId: String,
    private val apiKey: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
) {
    init {
        require(modelId.isNotBlank()) { "Model ID is blank" }
        require(apiKey.isNotBlank()) { "API key is blank" }
        require('\r' !in apiKey && '\n' !in apiKey) { "API key contains invalid characters" }
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

    internal inner class LogicalCall(
        private val text: String,
        private val onFinished: (LogicalCall) -> Unit,
        private val onResult: (Result<String>) -> Unit,
    ) : TranslationCall {
        private val cancelled = AtomicBoolean(false)
        private val completed = AtomicBoolean(false)

        @Volatile
        private var networkCall: Call? = null

        @Volatile
        private var retryFuture: ScheduledFuture<*>? = null

        fun start() {
            executeAttempt(attemptIndex = 0)
        }

        private fun executeAttempt(attemptIndex: Int) {
            if (cancelled.get() || completed.get()) return
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
                    response.use {
                        if (cancelled.get()) {
                            finish(Result.failure(CancellationException("Online request cancelled")))
                            return
                        }
                        if (!response.isSuccessful) {
                            val delay = OnlineHttpPolicy.retryDelayForStatus(
                                statusCode = response.code,
                                completedAttempts = attemptIndex,
                                retryAfter = response.header("Retry-After"),
                                fallbackDelayMillis = retryJitterMillis(),
                            )
                            if (delay != null) {
                                scheduleRetry(attemptIndex + 1, delay)
                            } else {
                                finish(
                                    Result.failure(
                                        OnlineHttpPolicy.failureForStatus(response.code),
                                    ),
                                )
                            }
                            return
                        }
                        val translated = runCatching {
                            OpenAiChatProtocol.parseTranslation(readBounded(response.body))
                        }
                        finish(
                            translated.fold(
                                onSuccess = { Result.success(it) },
                                onFailure = {
                                    Result.failure(OnlineHttpPolicy.sanitizeNetworkFailure(it))
                                },
                            ),
                        )
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

        private fun finish(result: Result<String>) {
            if (!completed.compareAndSet(false, true)) return
            retryFuture?.cancel(false)
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
        )
        return Request.Builder()
            .url(endpoint.requestUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun readBounded(body: ResponseBody): String {
        val declaredSize = body.contentLength()
        if (declaredSize > MAX_RESPONSE_BYTES) {
            throw OnlineTranslationException(OnlineFailureCategory.RESPONSE)
        }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream(
                declaredSize.takeIf { it in 1..MAX_RESPONSE_BYTES }
                    ?.toInt()
                    ?: DEFAULT_RESPONSE_BUFFER_BYTES,
            )
            val buffer = ByteArray(DEFAULT_RESPONSE_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw OnlineTranslationException(OnlineFailureCategory.RESPONSE)
                }
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun retryJitterMillis(): Long =
        ThreadLocalRandom.current().nextLong(MIN_RETRY_JITTER_MILLIS, MAX_RETRY_JITTER_MILLIS + 1L)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_INPUT_CHARACTERS = 6_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024L
        const val DEFAULT_RESPONSE_BUFFER_BYTES = 8 * 1024
        const val MIN_RETRY_JITTER_MILLIS = 300L
        const val MAX_RETRY_JITTER_MILLIS = 800L
    }
}
