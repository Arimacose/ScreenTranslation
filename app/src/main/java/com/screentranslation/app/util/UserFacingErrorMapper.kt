package com.screentranslation.app.util

import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

data class UserFacingError(
    val summary: String,
    val technicalCode: String,
    val redactedDetail: String,
)

/** Edition-specific failures expose already-redacted wording without coupling shared code to a flavor. */
internal interface UserFacingFailureMetadata {
    val userFacingSummary: String
    val userFacingTechnicalCode: String
}

/** Prevents a continuous capture loop from stacking the same Toast every frame. */
internal class UserFeedbackThrottle(
    private val duplicateCooldownMillis: Long,
) {
    private var lastKey: String? = null
    private var lastShownAtMillis = Long.MIN_VALUE

    init {
        require(duplicateCooldownMillis >= 0L)
    }

    fun shouldShow(key: String, nowMillis: Long): Boolean {
        val isDuplicateInsideCooldown = key == lastKey &&
            nowMillis >= lastShownAtMillis &&
            nowMillis - lastShownAtMillis < duplicateCooldownMillis
        if (isDuplicateInsideCooldown) return false
        lastKey = key
        lastShownAtMillis = nowMillis
        return true
    }

    fun reset() {
        lastKey = null
        lastShownAtMillis = Long.MIN_VALUE
    }
}

/** Stable Chinese summaries for shared UI/service surfaces; technical text is opt-in only. */
object UserFacingErrorMapper {
    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;\\\"]+")
    private val secretAssignment = Regex(
        "(?i)(api[_ -]?key|authorization|token|secret)(\\s*[:=]\\s*)([^\\s,;&]+)",
    )
    private val secretQuery = Regex("(?i)([?&](?:api[_-]?key|token|secret)=)[^&#\\s]+")

    fun map(error: Throwable): UserFacingError {
        val (summary, code) = when (error) {
            is UserFacingFailureMetadata ->
                error.userFacingSummary to error.userFacingTechnicalCode
            is CancellationException -> "操作已取消，现有状态保持不变" to "CANCELLED"
            is UnknownHostException -> "服务地址解析失败，请检查地址和网络" to "DNS"
            is SSLException -> "安全连接建立失败，请检查 HTTPS 服务证书" to "TLS"
            is InterruptedIOException, is TimeoutException ->
                "OCR 或翻译模型处理超时，请稍后重试" to "TIMEOUT"
            is SecurityException -> "权限状态已变化，请重新确认所需权限" to "PERMISSION"
            is IllegalArgumentException -> "输入或配置不完整，请检查后重试" to "INVALID_INPUT"
            is IllegalStateException -> "当前资源状态不满足操作条件，请刷新后重试" to "STATE"
            else -> "操作未完成，请稍后重试" to error.javaClass.simpleName
                .uppercase()
                .take(MAX_CODE_LENGTH)
        }
        return UserFacingError(
            summary = summary,
            technicalCode = code,
            redactedDetail = redact(
                listOfNotNull(error.javaClass.simpleName, error.message)
                    .joinToString(": "),
            ),
        )
    }

    fun redact(value: String): String = value
        .replace(bearer, "Bearer [REDACTED]")
        .replace(secretAssignment) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        .replace(secretQuery) { match -> "${match.groupValues[1]}[REDACTED]" }
        .lineSequence()
        .take(MAX_DETAIL_LINES)
        .joinToString("\n")
        .take(MAX_DETAIL_LENGTH)

    private const val MAX_CODE_LENGTH = 64
    private const val MAX_DETAIL_LINES = 8
    private const val MAX_DETAIL_LENGTH = 1_024
}
