package com.screentranslation.app.online

import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import com.screentranslation.app.ml.TranslationCall
import javax.net.ssl.SSLException

internal data class OnlineModelDescriptor(
    val id: String,
    val displayName: String? = null,
    val owner: String? = null,
    val createdAtEpochSeconds: Long? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none(Char::isISOControl))
        require(displayName == null || displayName.length <= MAX_METADATA_LENGTH)
        require(owner == null || owner.length <= MAX_METADATA_LENGTH)
    }

    val label: String
        get() = displayName?.takeIf { it.isNotBlank() && it != id }
            ?.let { "$it  ($id)" }
            ?: id

    companion object {
        const val MAX_ID_LENGTH = 256
        const val MAX_METADATA_LENGTH = 256
    }
}

internal object OnlineModelSearchIndex {
    const val MAX_MODELS = 1_000

    fun filter(models: List<OnlineModelDescriptor>, query: String): List<OnlineModelDescriptor> {
        require(models.size <= MAX_MODELS)
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return models
        return models.filter { model ->
            val searchable = listOfNotNull(model.id, model.displayName, model.owner)
                .joinToString("\n")
                .lowercase()
            terms.all(searchable::contains)
        }
    }
}

internal enum class OnlineOperationState { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }

internal data class OnlineUserFacingFailure(
    val category: OnlineFailureCategory,
    val summary: String,
    val technicalCode: String,
    val redactedDetail: String,
    val retryable: Boolean,
)

/** A single centralized boundary between exceptions and anything rendered or recorded. */
internal object OnlineFailureMapper {
    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;\\\"]+")
    private val secretAssignment = Regex(
        "(?i)(api[_ -]?key|authorization|token|secret)(\\s*[:=]\\s*)([^\\s,;&]+)",
    )
    private val secretQuery = Regex("(?i)([?&](?:api[_-]?key|token|secret)=)[^&#\\s]+")

    fun map(error: Throwable): OnlineUserFacingFailure {
        val category = when (error) {
            is OnlineTranslationException -> error.category
            is CancellationException -> OnlineFailureCategory.NETWORK
            is InterruptedIOException -> OnlineFailureCategory.TIMEOUT
            is UnknownHostException -> OnlineFailureCategory.DNS
            is SSLException -> OnlineFailureCategory.TLS
            is IllegalArgumentException -> OnlineFailureCategory.REQUEST_CONTRACT
            else -> OnlineFailureCategory.NETWORK
        }
        val cancelled = error is CancellationException
        val summary = if (cancelled) "操作已取消；已保存的配置保持不变" else category.displayMessage
        val status = (error as? OnlineTranslationException)?.statusCode
        return OnlineUserFacingFailure(
            category = category,
            summary = summary,
            technicalCode = if (cancelled) "CANCELLED" else status?.let { "HTTP_$it" }
                ?: category.name,
            redactedDetail = redact(
                listOfNotNull(error.javaClass.simpleName, error.message)
                    .joinToString(": ")
                    .take(MAX_DETAIL_LENGTH),
            ),
            retryable = !cancelled && category in setOf(
                OnlineFailureCategory.RATE_LIMIT,
                OnlineFailureCategory.TEMPORARY_SERVICE,
                OnlineFailureCategory.SERVER,
                OnlineFailureCategory.TIMEOUT,
                OnlineFailureCategory.NETWORK,
            ),
        )
    }

    fun redact(value: String): String = value
        .replace(bearer, "Bearer [REDACTED]")
        .replace(secretAssignment) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
        .replace(secretQuery) { match -> "${match.groupValues[1]}[REDACTED]" }
        .take(MAX_DETAIL_LENGTH)

    private const val MAX_DETAIL_LENGTH = 1_024
}

internal enum class OnlineRequestOutcome { SUCCEEDED, FAILED, CANCELLED }

/** Content-free request telemetry suitable for UI diagnostics and acceptance artifacts. */
internal data class OnlineRequestMetric(
    val requestId: String,
    val modelId: String,
    val httpStatus: Int?,
    val latencyMillis: Long,
    val inputCharacters: Int,
    val outputCharacters: Int,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val attempts: Int,
    val outcome: OnlineRequestOutcome,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(modelId.length <= OnlineModelDescriptor.MAX_ID_LENGTH)
        require(latencyMillis >= 0 && inputCharacters >= 0 && outputCharacters >= 0)
        require(attempts in 1..OnlineHttpPolicy.MAX_ATTEMPTS)
    }
}

internal fun interface OnlineMetricsObserver {
    fun onMetric(metric: OnlineRequestMetric)

    companion object {
        val NONE = OnlineMetricsObserver {}
    }
}

internal data class OnlineBatchBlock(val id: String, val text: String)

internal object OnlineBatchContract {
    const val MAX_BLOCKS = 8
    const val MAX_CHARACTERS = 6_000
    const val MAX_SPLIT_DEPTH = 3
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")

    fun validateRequest(blocks: List<OnlineBatchBlock>) {
        require(blocks.isNotEmpty() && blocks.size <= MAX_BLOCKS) { "Batch block count is invalid" }
        require(blocks.sumOf { it.text.length } <= MAX_CHARACTERS) { "Batch is too large" }
        require(blocks.map { it.id }.toSet().size == blocks.size) { "Batch IDs are duplicated" }
        blocks.forEach { block ->
            require(block.id.matches(idPattern)) { "Batch ID is malformed" }
            require(block.text.isNotBlank()) { "Batch text is blank" }
        }
    }

    fun validateResponse(
        requested: List<OnlineBatchBlock>,
        translated: List<OnlineBatchBlock>,
    ): Map<String, String> {
        validateRequest(requested)
        require(translated.size == requested.size) { "Batch response is missing blocks" }
        require(translated.map { it.id }.toSet().size == translated.size) {
            "Batch response contains duplicate IDs"
        }
        val expected = requested.mapTo(linkedSetOf()) { it.id }
        val actual = translated.mapTo(linkedSetOf()) { it.id }
        require(actual == expected) { "Batch response contains missing or unexpected IDs" }
        translated.forEach { block ->
            require(block.id.matches(idPattern)) { "Batch response ID is malformed" }
            require(block.text.isNotBlank()) { "Batch translation is blank" }
            require(block.text.length <= OnlineChatClient.MAX_INPUT_CHARACTERS) {
                "Batch translation is too large"
            }
        }
        return translated.associateTo(linkedMapOf()) { it.id to it.text.trim() }
    }
}

/**
 * Settles one logical result. A failed multi-block call is bisected at most three times;
 * partial successes stay private until every requested ID has a validated translation.
 */
internal class OnlineBatchCoordinator(
    private val executeAttempt: (
        List<OnlineBatchBlock>,
        (Result<Map<String, String>>) -> Unit,
    ) -> TranslationCall,
) {
    fun translate(
        blocks: List<OnlineBatchBlock>,
        onResult: (Result<Map<String, String>>) -> Unit,
    ): TranslationCall {
        OnlineBatchContract.validateRequest(blocks)
        val cancelled = AtomicBoolean(false)
        val settled = AtomicBoolean(false)
        val calls = linkedSetOf<TranslationCall>()
        val results = linkedMapOf<String, String>()
        var outstanding = 0

        fun settle(result: Result<Map<String, String>>) {
            if (settled.compareAndSet(false, true)) onResult(result)
        }

        fun launch(part: List<OnlineBatchBlock>, depth: Int) {
            if (cancelled.get() || settled.get()) return
            var callRef: TranslationCall? = null
            synchronized(calls) {
                outstanding += 1
                val returned = executeAttempt(part) { attempt ->
                    synchronized(calls) {
                        callRef?.let(calls::remove)
                        outstanding -= 1
                    }
                if (cancelled.get() || settled.get()) return@executeAttempt
                attempt.fold(
                    onSuccess = { translated ->
                        runCatching {
                            val ordered = part.map { requested ->
                                OnlineBatchBlock(
                                    requested.id,
                                    translated[requested.id]
                                        ?: throw IllegalArgumentException("Batch result is missing an ID"),
                                )
                            }
                            OnlineBatchContract.validateResponse(part, ordered)
                        }.fold(
                            onSuccess = { validated ->
                                val done = synchronized(results) {
                                    validated.forEach { (id, text) ->
                                        require(results.put(id, text) == null) {
                                            "Batch result was published twice"
                                        }
                                    }
                                    synchronized(calls) { outstanding == 0 }
                                }
                                if (done) {
                                    val ordered = blocks.associateTo(linkedMapOf()) { block ->
                                        block.id to checkNotNull(results[block.id])
                                    }
                                    settle(Result.success(ordered))
                                }
                            },
                            onFailure = { settle(Result.failure(it)) },
                        )
                    },
                    onFailure = { error ->
                        if (part.size > 1 && depth < OnlineBatchContract.MAX_SPLIT_DEPTH) {
                            val midpoint = part.size / 2
                            launch(part.subList(0, midpoint), depth + 1)
                            launch(part.subList(midpoint, part.size), depth + 1)
                        } else {
                            settle(Result.failure(error))
                            synchronized(calls) { calls.toList() }.forEach(TranslationCall::cancel)
                        }
                    },
                )
                }
                callRef = returned
                if (!settled.get() && !cancelled.get()) calls += returned else returned.cancel()
            }
        }

        launch(blocks, 0)
        return TranslationCall {
            if (!cancelled.compareAndSet(false, true)) return@TranslationCall
            synchronized(calls) { calls.toList() }.forEach(TranslationCall::cancel)
            settle(Result.failure(CancellationException("Online batch cancelled")))
        }
    }
}
