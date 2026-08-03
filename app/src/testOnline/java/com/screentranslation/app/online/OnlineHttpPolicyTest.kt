package com.screentranslation.app.online

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executor

class OnlineHttpPolicyTest {
    @Test
    fun `maps status codes to user-safe categories`() {
        assertEquals(
            OnlineFailureCategory.CREDENTIALS,
            OnlineHttpPolicy.failureForStatus(401).category,
        )
        assertEquals(
            OnlineFailureCategory.ENDPOINT_OR_MODEL,
            OnlineHttpPolicy.failureForStatus(404).category,
        )
        assertEquals(
            OnlineFailureCategory.RATE_LIMIT,
            OnlineHttpPolicy.failureForStatus(429).category,
        )
        assertEquals(
            OnlineFailureCategory.TEMPORARY_SERVICE,
            OnlineHttpPolicy.failureForStatus(503).category,
        )
        assertTrue(OnlineHttpPolicy.failureForStatus(401).message.orEmpty().contains("API Key"))
        assertTrue(OnlineHttpPolicy.failureForStatus(429).message.orEmpty().contains("自动重试"))
    }

    @Test
    fun `retries a transient status once with bounded retry-after`() {
        assertEquals(
            2_000L,
            OnlineHttpPolicy.retryDelayForStatus(429, 0, "9"),
        )
        assertNull(OnlineHttpPolicy.retryDelayForStatus(429, 1, "1"))
        assertNull(OnlineHttpPolicy.retryDelayForStatus(401, 0, "1"))
    }

    @Test
    fun `classifies timeout and dns without exposing original messages`() {
        assertEquals(
            OnlineFailureCategory.TIMEOUT,
            (OnlineHttpPolicy.sanitizeNetworkFailure(SocketTimeoutException("secret"))
                as OnlineTranslationException).category,
        )
        assertEquals(
            OnlineFailureCategory.DNS,
            (OnlineHttpPolicy.sanitizeNetworkFailure(UnknownHostException("secret-host"))
                as OnlineTranslationException).category,
        )
    }

    @Test
    fun `does not retry a generation timeout`() {
        assertNull(
            OnlineHttpPolicy.retryDelayForNetwork(
                SocketTimeoutException("already may have been processed"),
                completedAttempts = 0,
            ),
        )
    }

    @Test
    fun `generation client allows a bounded long model response`() {
        val client = OnlineHttpClientFactory.create()

        assertEquals(ONLINE_CONNECT_TIMEOUT_SECONDS * 1_000L, client.connectTimeoutMillis.toLong())
        assertEquals(ONLINE_WRITE_TIMEOUT_SECONDS * 1_000L, client.writeTimeoutMillis.toLong())
        assertEquals(ONLINE_READ_TIMEOUT_SECONDS * 1_000L, client.readTimeoutMillis.toLong())
        assertEquals(ONLINE_CALL_TIMEOUT_SECONDS * 1_000L, client.callTimeoutMillis.toLong())
        OnlineHttpClientFactory.closeAsync(client, Executor(Runnable::run))
    }

    @Test
    fun `preserves an already sanitized response failure`() {
        val original = OnlineTranslationException(OnlineFailureCategory.RESPONSE)

        assertEquals(original, OnlineHttpPolicy.sanitizeNetworkFailure(original))
    }

    @Test
    fun `defers http resource cleanup to the supplied executor`() {
        val client = OkHttpClient()
        val tasks = ArrayDeque<Runnable>()
        val queuedExecutor = Executor(tasks::addLast)

        OnlineHttpClientFactory.closeAsync(client, queuedExecutor)

        assertFalse(client.dispatcher.executorService.isShutdown)
        assertEquals(1, tasks.size)

        tasks.removeFirst().run()

        assertTrue(client.dispatcher.executorService.isShutdown)
    }
}
