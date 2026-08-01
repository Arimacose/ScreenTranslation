package com.screentranslation.app.online

import org.junit.Assert.assertEquals
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
}
