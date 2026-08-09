package com.screentranslation.app.online

import com.screentranslation.app.RetainedModelReadiness
import com.screentranslation.app.ml.onlinePreparationIdentity
import com.screentranslation.app.retainedReadinessMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiEndpointTest {
    @Test
    fun `appends chat completions to a version base url`() {
        val endpoint = OpenAiEndpoint.parse("https://api.example.test/v1/")

        assertEquals("https://api.example.test/v1", endpoint.baseUrl)
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            endpoint.requestUrl.toString(),
        )
        assertEquals(
            "https://api.example.test/v1/models",
            endpoint.modelsUrl.toString(),
        )
        assertEquals("api.example.test", endpoint.host)
        assertEquals("api.example.test:443", endpoint.consentIdentity)
    }

    @Test
    fun `keeps an existing chat completions endpoint`() {
        val endpoint = OpenAiEndpoint.parse(
            "https://api.example.test/v1/chat/completions/",
        )

        assertEquals("https://api.example.test/v1", endpoint.baseUrl)
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            endpoint.requestUrl.toString(),
        )
        assertEquals(
            "https://api.example.test/v1/models",
            endpoint.modelsUrl.toString(),
        )
    }

    @Test
    fun `normalizes an existing models endpoint to the api base`() {
        val endpoint = OpenAiEndpoint.parse("https://api.example.test/v1/models/")

        assertEquals("https://api.example.test/v1", endpoint.baseUrl)
        assertEquals(
            "https://api.example.test/v1/models",
            endpoint.modelsUrl.toString(),
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            endpoint.requestUrl.toString(),
        )
    }

    @Test
    fun `rejects cleartext credentials query and fragment`() {
        val invalid = listOf(
            "http://api.example.test/v1",
            "https://user:secret@api.example.test/v1",
            "https://api.example.test/v1?key=value",
            "https://api.example.test/v1#fragment",
        )

        invalid.forEach { value ->
            assertTrue(runCatching { OpenAiEndpoint.parse(value) }.isFailure)
        }
    }

    @Test
    fun `consent identity includes a non-default service port`() {
        assertEquals(
            "api.example.test:8443",
            OpenAiEndpoint.parse("https://api.example.test:8443/v1").consentIdentity,
        )
    }

    @Test
    fun `preparation identity binds every canonical BYOK field without exposing key`() {
        val apiKey = "sk-private-test-material"
        val baseline = onlinePreparationIdentity(
            baseUrl = "https://api.example.test/v1",
            modelId = "translation-model-v1",
            consentHost = "api.example.test:443",
            consentVersion = 1,
            apiKey = apiKey,
        ) ?: error("baseline identity was not created")

        assertEquals(64, baseline.length)
        assertFalse(baseline.contains(apiKey))
        assertNotEquals(
            baseline,
            onlinePreparationIdentity(
                "https://other.example.test/v1",
                "translation-model-v1",
                "api.example.test:443",
                1,
                apiKey,
            ),
        )
        assertNotEquals(
            baseline,
            onlinePreparationIdentity(
                "https://api.example.test/v1",
                "translation-model-v2",
                "api.example.test:443",
                1,
                apiKey,
            ),
        )
        assertNotEquals(
            baseline,
            onlinePreparationIdentity(
                "https://api.example.test/v1",
                "translation-model-v1",
                "other.example.test:443",
                1,
                apiKey,
            ),
        )
        assertNotEquals(
            baseline,
            onlinePreparationIdentity(
                "https://api.example.test/v1",
                "translation-model-v1",
                "api.example.test:443",
                2,
                apiKey,
            ),
        )
    }

    @Test
    fun `deleted or rotated API key invalidates retained readiness`() {
        val selectedPair = "en" to "zh"
        val originalIdentity = onlinePreparationIdentity(
            baseUrl = "https://api.example.test/v1",
            modelId = "translation-model-v1",
            consentHost = "api.example.test:443",
            consentVersion = 1,
            apiKey = "sk-original",
        ) ?: error("original identity was not created")
        val retained = RetainedModelReadiness(
            pair = selectedPair,
            identity = originalIdentity,
            generation = 1L,
        )
        assertTrue(retainedReadinessMatches(retained, selectedPair, originalIdentity))

        val deletedIdentity = onlinePreparationIdentity(
            baseUrl = "https://api.example.test/v1",
            modelId = "translation-model-v1",
            consentHost = "api.example.test:443",
            consentVersion = 1,
            apiKey = null,
        )
        assertNull(deletedIdentity)
        assertFalse(retainedReadinessMatches(retained, selectedPair, deletedIdentity))

        val rotatedIdentity = onlinePreparationIdentity(
            baseUrl = "https://api.example.test/v1",
            modelId = "translation-model-v1",
            consentHost = "api.example.test:443",
            consentVersion = 1,
            apiKey = "sk-rotated",
        )
        assertNotEquals(originalIdentity, rotatedIdentity)
        assertFalse(retainedReadinessMatches(retained, selectedPair, rotatedIdentity))
        assertNull(
            onlinePreparationIdentity(
                "https://api.example.test/v1",
                "translation-model-v1",
                "api.example.test:443",
                1,
                "   ",
            ),
        )
    }
}
