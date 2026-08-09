package com.screentranslation.app.capture

import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationCall
import com.screentranslation.app.ml.TranslationProviderProfile
import com.screentranslation.app.ml.TranslationProviderProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockTranslationQueueTest {
    @Test
    fun `runs one translation at a time in reading order`() {
        val backend = FakeTranslationBackend()
        val results = mutableListOf<Triple<Long, String, String>>()
        val queue = BlockTranslationQueue(backend, { id, source, translation ->
            results += Triple(id, source, translation)
        }, { throw AssertionError(it) })

        queue.submit(1, "first")
        queue.submit(2, "second")
        assertEquals(listOf("first"), backend.requests.map { it.text })

        backend.complete(0, "第一")
        assertEquals(listOf("first", "second"), backend.requests.map { it.text })
        backend.complete(1, "第二")

        assertEquals(
            listOf(Triple(1L, "first", "第一"), Triple(2L, "second", "第二")),
            results,
        )
    }

    @Test
    fun `protects a monetary value and restores it before publishing`() {
        val backend = FakeTranslationBackend()
        var result = ""
        val queue = BlockTranslationQueue(
            backend,
            { _, _, translation -> result = translation },
            { throw AssertionError(it) },
        )

        queue.submit(7, "Total is ￥1,299.00")
        assertFalse(backend.requests.single().text.contains("￥1,299.00"))
        backend.complete(0, backend.requests.single().text.replace("Total is", "合计"))

        assertEquals("合计 ￥1,299.00", result)
    }

    @Test
    fun `removing an active block cancels it and advances to a retained block`() {
        val backend = FakeTranslationBackend()
        val queue = BlockTranslationQueue(backend, { _, _, _ -> }, { throw AssertionError(it) })
        queue.submit(1, "removed")
        queue.submit(2, "retained")

        queue.synchronize(setOf(2))

        assertTrue(backend.requests[0].cancelled)
        assertEquals("retained", backend.requests[1].text)
    }

    @Test
    fun `pause survives submit and reset until explicit resume`() {
        val backend = FakeTranslationBackend()
        val queue = BlockTranslationQueue(backend, { _, _, _ -> }, { throw AssertionError(it) })

        queue.pause()
        queue.submit(1, "queued while hidden")
        assertTrue(backend.requests.isEmpty())

        queue.reset()
        queue.submit(2, "queued after reset")
        assertTrue(backend.requests.isEmpty())

        queue.resume()
        assertEquals(listOf("queued after reset"), backend.requests.map { it.text })
    }

    @Test
    fun `changed text in the same block cancels stale translation immediately`() {
        val backend = FakeTranslationBackend()
        val results = mutableListOf<String>()
        val queue = BlockTranslationQueue(
            backend,
            { _, _, translation -> results += translation },
            { throw AssertionError(it) },
        )

        queue.submit(9, "old subtitle")
        queue.submit(9, "new subtitle")

        assertTrue(backend.requests[0].cancelled)
        assertEquals(listOf("old subtitle", "new subtitle"), backend.requests.map { it.text })
        backend.complete(0, "旧字幕")
        backend.complete(1, "新字幕")
        assertEquals(listOf("新字幕"), results)
    }
}

private class FakeTranslationBackend : TranslationBackend {
    data class Request(
        val text: String,
        val callback: (Result<String>) -> Unit,
        var cancelled: Boolean = false,
    )

    val requests = mutableListOf<Request>()

    override val profile: TranslationProviderProfile = TranslationProviderProfiles.bergamotLite

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ): TranslationCall {
        onResult(Result.success(Unit))
        return TranslationCall.NONE
    }

    override fun translate(text: String, onResult: (Result<String>) -> Unit): TranslationCall {
        val request = Request(text, onResult)
        requests += request
        return TranslationCall { request.cancelled = true }
    }

    fun complete(index: Int, translation: String) {
        requests[index].callback(Result.success(translation))
    }

    override fun close() = Unit
}
