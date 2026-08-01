package com.screentranslation.app.online

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal enum class OnlineFailureCategory(val displayMessage: String) {
    CREDENTIALS("凭据或服务权限错误"),
    ENDPOINT_OR_MODEL("服务地址、模型列表或所选模型错误"),
    RATE_LIMIT("服务请求限流"),
    TEMPORARY_SERVICE("翻译服务暂时异常"),
    REQUEST_CONTRACT("服务不接受当前请求"),
    SERVER("翻译服务返回错误"),
    DNS("服务主机解析失败"),
    TLS("TLS 连接失败"),
    TIMEOUT("翻译请求超时"),
    NETWORK("网络请求失败"),
    RESPONSE("翻译响应格式错误"),
}

internal class OnlineTranslationException(
    val category: OnlineFailureCategory,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append(category.displayMessage)
        statusCode?.let { append(" (HTTP $it)") }
    },
    cause,
)

internal object OnlineHttpPolicy {
    const val MAX_ATTEMPTS = 2
    const val MAX_RETRY_DELAY_MILLIS = 2_000L

    fun failureForStatus(statusCode: Int): OnlineTranslationException =
        OnlineTranslationException(
            category = when (statusCode) {
                401, 403 -> OnlineFailureCategory.CREDENTIALS
                404 -> OnlineFailureCategory.ENDPOINT_OR_MODEL
                429 -> OnlineFailureCategory.RATE_LIMIT
                408, 502, 503, 504 -> OnlineFailureCategory.TEMPORARY_SERVICE
                in 400..499 -> OnlineFailureCategory.REQUEST_CONTRACT
                else -> OnlineFailureCategory.SERVER
            },
            statusCode = statusCode,
        )

    fun retryDelayForStatus(
        statusCode: Int,
        completedAttempts: Int,
        retryAfter: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
        fallbackDelayMillis: Long = 500L,
    ): Long? {
        if (completedAttempts >= MAX_ATTEMPTS - 1) return null
        if (statusCode !in RETRYABLE_STATUS_CODES) return null
        return parseRetryAfter(retryAfter, nowEpochMillis)
            ?: fallbackDelayMillis.coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
    }

    fun retryDelayForNetwork(
        error: IOException,
        completedAttempts: Int,
        fallbackDelayMillis: Long = 500L,
    ): Long? {
        if (completedAttempts >= MAX_ATTEMPTS - 1) return null
        if (error is SSLException || error is UnknownHostException) return null
        return fallbackDelayMillis.coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
    }

    fun sanitizeNetworkFailure(error: Throwable): Throwable = when (error) {
        is OnlineTranslationException -> error
        is CancellationException -> error
        is SocketTimeoutException ->
            OnlineTranslationException(OnlineFailureCategory.TIMEOUT, cause = error)
        is UnknownHostException ->
            OnlineTranslationException(OnlineFailureCategory.DNS, cause = error)
        is SSLException ->
            OnlineTranslationException(OnlineFailureCategory.TLS, cause = error)
        is IOException ->
            OnlineTranslationException(OnlineFailureCategory.NETWORK, cause = error)
        is IllegalArgumentException ->
            OnlineTranslationException(OnlineFailureCategory.RESPONSE, cause = error)
        else -> OnlineTranslationException(OnlineFailureCategory.RESPONSE, cause = error)
    }

    private fun parseRetryAfter(value: String?, nowEpochMillis: Long): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalized.toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L)
                .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
        return runCatching {
            val target = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (target - nowEpochMillis).coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
        }.getOrNull()
    }

    private val RETRYABLE_STATUS_CODES = setOf(408, 429, 502, 503, 504)
}

internal object OnlineHttpClientFactory {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10L, TimeUnit.SECONDS)
        .writeTimeout(10L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .callTimeout(40L, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
