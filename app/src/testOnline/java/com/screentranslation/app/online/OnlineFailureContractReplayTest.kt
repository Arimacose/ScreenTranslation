package com.screentranslation.app.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class OnlineFailureContractReplayTest {
    @Test
    fun `public failure contract matches the Online policy and parser`() {
        val contract = JSONObject(
            checkNotNull(
                javaClass.getResourceAsStream("/online-failure-contract.json"),
            ).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        assertEquals(1, contract.getInt("schema_version"))
        assertEquals("Apache-2.0", contract.getString("license_spdx"))

        val cases = contract.getJSONArray("cases")
        assertEquals(9, cases.length())
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val stimulus = fixture.getJSONObject("stimulus")
            val expected = fixture.getJSONObject("expected")
            assertTrue(expected.getBoolean("preserve_previous_translation"))

            when (stimulus.getString("kind")) {
                "http" -> verifyHttpFixture(stimulus, expected)
                "transport" -> verifyTransportFixture(stimulus, expected)
                else -> error("Unknown failure stimulus: $stimulus")
            }
        }
    }

    private fun verifyHttpFixture(
        stimulus: JSONObject,
        expected: JSONObject,
    ) {
        val status = stimulus.getInt("status")
        if (status == 200) {
            val payload = when (stimulus.getString("body_fixture")) {
                "malformed_json" -> "not-json"
                "empty_assistant_content" ->
                    """{"choices":[{"message":{"content":"  "}}]}"""
                else -> error("Unknown response fixture: $stimulus")
            }
            val failure = runCatching {
                OpenAiChatProtocol.parseTranslation(payload)
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure is IllegalArgumentException)
            assertFalse(expected.getBoolean("retry"))
            assertEquals(1, expected.getInt("maximum_attempts"))
            return
        }

        val actualClassification = when (
            OnlineHttpPolicy.failureForStatus(status).category
        ) {
            OnlineFailureCategory.CREDENTIALS -> "credentials"
            OnlineFailureCategory.RATE_LIMIT -> "rate_limit"
            OnlineFailureCategory.TEMPORARY_SERVICE -> "temporary_service"
            OnlineFailureCategory.SERVER -> "server"
            else -> error("Unexpected category for HTTP $status")
        }
        assertEquals(expected.getString("classification"), actualClassification)

        val retryAfter = stimulus.optJSONObject("headers")
            ?.optString("Retry-After")
            ?.takeIf { it.isNotEmpty() }
        val delay = OnlineHttpPolicy.retryDelayForStatus(
            statusCode = status,
            completedAttempts = 0,
            retryAfter = retryAfter,
            nowEpochMillis = 0L,
            fallbackDelayMillis = 500L,
        )
        if (expected.getBoolean("retry")) {
            assertNotNull(delay)
            assertEquals(OnlineHttpPolicy.MAX_ATTEMPTS, expected.getInt("maximum_attempts"))
            assertTrue(delay!! >= expected.getLong("minimum_retry_delay_ms"))
        } else {
            assertNull(delay)
            assertEquals(1, expected.getInt("maximum_attempts"))
        }
    }

    private fun verifyTransportFixture(
        stimulus: JSONObject,
        expected: JSONObject,
    ) {
        assertEquals("read_timeout", stimulus.getString("exception"))
        val error = SocketTimeoutException("fixture timeout")
        val sanitized = OnlineHttpPolicy.sanitizeNetworkFailure(error)
        assertTrue(sanitized is OnlineTranslationException)
        assertEquals(
            OnlineFailureCategory.TIMEOUT,
            (sanitized as OnlineTranslationException).category,
        )
        assertEquals("timeout", expected.getString("classification"))
        assertFalse(expected.getBoolean("retry"))
        assertNull(OnlineHttpPolicy.retryDelayForNetwork(error, completedAttempts = 0))
        assertEquals(1, expected.getInt("maximum_attempts"))
    }
}
