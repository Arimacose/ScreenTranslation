package com.screentranslation.app.online

import com.screentranslation.app.ml.TranslationCall
import com.screentranslation.app.ml.BatchTranslationBackend
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBatchItem
import com.screentranslation.app.ml.TranslationProviderProfile
import com.screentranslation.app.ml.TranslationProviderProfiles
import com.screentranslation.app.capture.BlockTranslationQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class OnlineV24ContractsTest {
    @Test
    fun `large catalog search preserves exact stable IDs and metadata labels`() {
        val models = (0 until 1_000).map { index ->
            OnlineModelDescriptor(
                id = "exact/model-$index",
                displayName = if (index == 731) "Fast Japanese Translator" else null,
                owner = if (index == 731) "Provider A" else "Provider B",
            )
        }
        val result = OnlineModelSearchIndex.filter(models, "japanese provider a")

        assertEquals(listOf("exact/model-731"), result.map { it.id })
        assertTrue(result.single().label.contains("exact/model-731"))
    }

    @Test
    fun `failure mapper covers stable outcomes and redacts credential forms`() {
        val fixtures = listOf(
            OnlineHttpPolicy.failureForStatus(401),
            OnlineHttpPolicy.failureForStatus(403),
            OnlineHttpPolicy.failureForStatus(404),
            OnlineHttpPolicy.failureForStatus(408),
            OnlineHttpPolicy.failureForStatus(429),
            OnlineHttpPolicy.failureForStatus(503),
            java.net.UnknownHostException("api_key=visible"),
            javax.net.ssl.SSLException("Authorization: Bearer visible"),
            java.io.InterruptedIOException("token=visible"),
            CancellationException("Bearer visible"),
        )
        val mapped = fixtures.map(OnlineFailureMapper::map)

        assertEquals(10, mapped.size)
        assertTrue(mapped.all { it.summary.isNotBlank() && it.technicalCode.isNotBlank() })
        assertTrue(mapped.all { "visible" !in it.redactedDetail })
        assertTrue(mapped.last().summary.contains("取消"))
    }

    @Test
    fun `strict batch rejects missing duplicate unexpected malformed and blank results`() {
        val request = listOf(OnlineBatchBlock("a", "first"), OnlineBatchBlock("b", "second"))
        val invalid = listOf(
            listOf(OnlineBatchBlock("a", "甲")),
            listOf(OnlineBatchBlock("a", "甲"), OnlineBatchBlock("a", "乙")),
            listOf(OnlineBatchBlock("a", "甲"), OnlineBatchBlock("c", "丙")),
            listOf(OnlineBatchBlock("bad id", "甲"), OnlineBatchBlock("b", "乙")),
            listOf(OnlineBatchBlock("a", " "), OnlineBatchBlock("b", "乙")),
        )
        invalid.forEach { response ->
            assertTrue(runCatching { OnlineBatchContract.validateResponse(request, response) }.isFailure)
        }
        assertEquals(
            mapOf("a" to "甲", "b" to "乙"),
            OnlineBatchContract.validateResponse(
                request,
                listOf(OnlineBatchBlock("a", "甲"), OnlineBatchBlock("b", "乙")),
            ),
        )
    }

    @Test
    fun `failed batch splits within bound and publishes one complete ordered result`() {
        val attempts = mutableListOf<List<String>>()
        val callbacks = ArrayDeque<Pair<List<OnlineBatchBlock>, (Result<Map<String, String>>) -> Unit>>()
        val coordinator = OnlineBatchCoordinator { blocks, callback ->
            attempts += blocks.map { it.id }
            callbacks += blocks to callback
            TranslationCall.NONE
        }
        val blocks = (0 until 8).map { OnlineBatchBlock("b$it", "text-$it") }
        val publications = mutableListOf<Result<Map<String, String>>>()
        coordinator.translate(blocks) { publications += it }

        callbacks.removeFirst().second(Result.failure(IllegalStateException("split")))
        while (callbacks.isNotEmpty()) {
            val (part, callback) = callbacks.removeFirst()
            callback(Result.success(part.associate { it.id to "translated-${it.id}" }))
        }

        assertEquals(listOf(8, 4, 4), attempts.map { it.size })
        assertEquals(1, publications.size)
        assertEquals(blocks.map { it.id }, publications.single().getOrThrow().keys.toList())
    }

    @Test
    fun `synchronous transport completion settles once without retaining call`() {
        val coordinator = OnlineBatchCoordinator { blocks, callback ->
            callback(Result.success(blocks.associate { it.id to "ok:${it.id}" }))
            TranslationCall.NONE
        }
        val results = mutableListOf<Result<Map<String, String>>>()
        coordinator.translate(listOf(OnlineBatchBlock("one", "text"))) { results += it }
        assertEquals(listOf("one"), results.single().getOrThrow().keys.toList())
    }

    @Test
    fun `content-free metric exposes counts but has no content field`() {
        val metric = OnlineRequestMetric(
            requestId = "req-1",
            modelId = "model-a",
            httpStatus = 200,
            latencyMillis = 42,
            inputCharacters = 10,
            outputCharacters = 7,
            promptTokens = 4,
            completionTokens = 3,
            attempts = 1,
            outcome = OnlineRequestOutcome.SUCCEEDED,
        )
        assertEquals(10, metric.inputCharacters)
        assertFalse(
            metric.javaClass.declaredFields.any { it.name in setOf("text", "content", "apiKey") },
        )
    }

    @Test
    fun `full screen queue batches blocks and discards a stale cancelled batch`() {
        val backend = FakeBatchBackend()
        val published = mutableListOf<Triple<Long, String, String>>()
        val queue = BlockTranslationQueue(
            backend,
            { id, source, translation -> published += Triple(id, source, translation) },
            { throw AssertionError(it) },
        )
        queue.submitAll(listOf(1L to "first", 2L to "second", 3L to "third"))
        assertEquals(1, backend.requests.size)
        assertEquals(3, backend.requests.single().items.size)

        queue.submitAll(listOf(2L to "changed", 3L to "third"))
        assertTrue(backend.requests[0].cancelled)
        backend.complete(0)
        assertTrue(published.isEmpty())
        backend.complete(1)

        assertEquals(listOf(2L, 3L, 1L), published.map { it.first }.sortedBy { if (it == 1L) 2 else 1 })
        assertTrue(published.any { it.first == 2L && it.second == "changed" })
    }
}

private class FakeBatchBackend : TranslationBackend, BatchTranslationBackend {
    data class Request(
        val items: List<TranslationBatchItem>,
        val callback: (Result<Map<String, String>>) -> Unit,
        var cancelled: Boolean = false,
    )

    val requests = mutableListOf<Request>()
    override val profile: TranslationProviderProfile = TranslationProviderProfiles.onlineByok
    override val maximumBatchItems = 8
    override val maximumBatchCharacters = 6_000

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ): TranslationCall = TranslationCall.NONE.also { onResult(Result.success(Unit)) }

    override fun translate(text: String, onResult: (Result<String>) -> Unit): TranslationCall =
        error("Single translation is not expected")

    override fun translateBatch(
        items: List<TranslationBatchItem>,
        onResult: (Result<Map<String, String>>) -> Unit,
    ): TranslationCall {
        val request = Request(items, onResult)
        requests += request
        return TranslationCall { request.cancelled = true }
    }

    fun complete(index: Int) {
        val request = requests[index]
        request.callback(Result.success(request.items.associate { it.id to "zh:${it.text}" }))
    }

    override fun close() = Unit
}
