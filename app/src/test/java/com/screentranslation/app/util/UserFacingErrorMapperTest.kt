package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

class UserFacingErrorMapperTest {
    @Test
    fun `maps common failures to stable Chinese summaries`() {
        val cases = listOf(
            CancellationException() to "CANCELLED",
            UnknownHostException() to "DNS",
            SSLException("bad") to "TLS",
            InterruptedIOException() to "TIMEOUT",
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
}
