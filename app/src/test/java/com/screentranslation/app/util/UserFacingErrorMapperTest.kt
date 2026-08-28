package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

class UserFacingErrorMapperTest {
    @Test
    fun `maps common failures to stable Chinese summaries`() {
        val cases = listOf(
            CancellationException() to "CANCELLED",
            UnknownHostException() to "DNS",
            SSLException("bad") to "TLS",
            InterruptedIOException() to "TIMEOUT",
            TimeoutException() to "TIMEOUT",
            SecurityException() to "PERMISSION",
            IllegalArgumentException() to "INVALID_INPUT",
            IllegalStateException() to "STATE",
        )
        cases.forEach { (error, code) ->
            val mapped = UserFacingErrorMapper.map(error)
            assertEquals(code, mapped.technicalCode)
            assertTrue(mapped.summary.any { it.code > 127 })
        }
    }

    @Test
    fun `redacts credentials from technical detail`() {
        val mapped = UserFacingErrorMapper.map(
            IllegalStateException(
                "Authorization: Bearer visible api_key=also-visible https://x.test?p=1&token=third",
            ),
        )
        assertFalse(mapped.redactedDetail.contains("visible"))
        assertFalse(mapped.redactedDetail.contains("third"))
        assertTrue(mapped.redactedDetail.contains("[REDACTED]"))
    }

    @Test
    fun `edition metadata keeps an actionable redacted summary`() {
        val error = object : IllegalStateException("internal detail"), UserFacingFailureMetadata {
            override val userFacingSummary = "API Key 无效，请检查配置"
            override val userFacingTechnicalCode = "HTTP_401"
        }

        val mapped = UserFacingErrorMapper.map(error)

        assertEquals("API Key 无效，请检查配置", mapped.summary)
        assertEquals("HTTP_401", mapped.technicalCode)
    }

    @Test
    fun `feedback throttle suppresses only duplicate messages inside cooldown`() {
        val throttle = UserFeedbackThrottle(8_000L)

        assertTrue(throttle.shouldShow("timeout", 1_000L))
        assertFalse(throttle.shouldShow("timeout", 8_999L))
        assertTrue(throttle.shouldShow("invalid-key", 9_000L))
        assertFalse(throttle.shouldShow("invalid-key", 16_999L))
        assertTrue(throttle.shouldShow("invalid-key", 17_000L))
        throttle.reset()
        assertTrue(throttle.shouldShow("invalid-key", 17_001L))
    }
}
