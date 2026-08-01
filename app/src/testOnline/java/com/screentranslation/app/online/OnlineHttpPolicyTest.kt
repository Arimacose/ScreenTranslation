package com.screentranslation.app.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun `preserves an already sanitized response failure`() {
        val original = OnlineTranslationException(OnlineFailureCategory.RESPONSE)

        assertEquals(original, OnlineHttpPolicy.sanitizeNetworkFailure(original))
    }
}
