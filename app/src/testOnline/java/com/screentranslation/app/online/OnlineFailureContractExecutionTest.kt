package com.screentranslation.app.online

import com.screentranslation.app.overlay.RegionOverlayContentPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Executes the versioned contract through production Kotlin policy/parser
 * code. A formal gate passes a one-use challenge and an empty, gate-owned output
 * path. Ordinary unit-test runs assert the contract without creating a reusable
 * formal evidence file.
 */
class OnlineFailureContractExecutionTest {
    @Test
    fun `production policy parser and region retention satisfy public contract`() {
        val contractBytes = checkNotNull(
            javaClass.getResourceAsStream("/online-failure-contract.json"),
        ).use { it.readBytes() }
        val contract = JSONObject(contractBytes.toString(Charsets.UTF_8))
        assertEquals(2, contract.getInt("schema_version"))
        assertEquals("Apache-2.0", contract.getString("license_spdx"))

        val evidenceCases = JSONArray()
        val cases = contract.getJSONArray("cases")
        assertEquals(9, cases.length())
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val actual = executeStimulus(fixture.getJSONObject("stimulus"))
            compareEveryField(
                caseId = fixture.getString("id"),
                expected = fixture.getJSONObject("expected"),
                actual = actual,
            )
            evidenceCases.put(
                JSONObject()
                    .put("case_id", fixture.getString("id"))
                    .put("actual", actual),
            )
        }

        val request = OnlineFailureEvidenceProtocol.requestFromSystemProperties(
            System.getProperties().stringPropertyNames().associateWith { key ->
                System.getProperty(key).orEmpty()
            },
        ) ?: return
        val root = repositoryRoot()
        val producer = root.resolve(PRODUCER_SOURCE)
        val contractSha256 = OnlineFailureEvidenceProtocol.sha256(contractBytes)
        val producerSha256 = OnlineFailureEvidenceProtocol.sha256(Files.readAllBytes(producer))
        val executionChainSha256 = OnlineFailureEvidenceProtocol.executionChainSha256(root)
        val challengeBytes = Files.readAllBytes(request.challengePath)
        val challenge = OnlineFailureEvidenceProtocol.parseChallenge(
            challengeBytes = challengeBytes,
            expectedContractSha256 = contractSha256,
            expectedProducerSourceSha256 = producerSha256,
            expectedExecutionChainSha256 = executionChainSha256,
        )
        val evidence = OnlineFailureEvidenceProtocol.buildEvidence(
            challenge = challenge,
            contractSha256 = contractSha256,
            producerSourceSha256 = producerSha256,
            evidenceCases = evidenceCases,
        )
        OnlineFailureEvidenceProtocol.writeNewEvidence(request.outputPath, evidence)
        assertTrue(Files.size(request.outputPath) > 0L)
    }

    private fun executeStimulus(stimulus: JSONObject): JSONObject {
        val failure: OnlineTranslationException
        val retryDelay: Long?
        when (stimulus.getString("kind")) {
            "http" -> {
                val status = stimulus.getInt("status")
                if (status == 200) {
                    val payload = when (stimulus.getString("body_fixture")) {
                        "malformed_json" -> "not-json"
                        "empty_assistant_content" ->
                            """{"choices":[{"message":{"content":"  "}}]}"""
                        else -> error("Unknown response body fixture: $stimulus")
                    }
                    val parserError = checkNotNull(
                        runCatching {
                            OpenAiChatProtocol.parseTranslation(payload)
                        }.exceptionOrNull(),
                    )
                    failure = OnlineHttpPolicy.sanitizeNetworkFailure(parserError)
                        as OnlineTranslationException
                    retryDelay = null
                } else {
                    failure = OnlineHttpPolicy.failureForStatus(status)
                    val retryAfter = stimulus.optJSONObject("headers")
                        ?.optString("Retry-After")
                        ?.takeIf(String::isNotEmpty)
                    retryDelay = OnlineHttpPolicy.retryDelayForStatus(
                        statusCode = status,
                        completedAttempts = 0,
                        retryAfter = retryAfter,
                        nowEpochMillis = 0L,
                        fallbackDelayMillis = 500L,
                    )
                }
            }

            "transport" -> {
                check(stimulus.getString("exception") == "read_timeout")
                val transportError = SocketTimeoutException("contract read timeout")
                failure = OnlineHttpPolicy.sanitizeNetworkFailure(transportError)
                    as OnlineTranslationException
                retryDelay = OnlineHttpPolicy.retryDelayForNetwork(
                    transportError,
                    completedAttempts = 0,
                )
            }

            else -> error("Unknown failure stimulus: $stimulus")
        }

        val retry = retryDelay != null
        val previous = RegionOverlayContentPolicy.success(
            original = "previous source",
            translation = "上一条可用译文",
        )
        val pending = RegionOverlayContentPolicy.pending(
            currentOriginal = previous.original,
            currentTranslation = previous.translation,
            nextOriginal = "latest source",
        )
        val failed = RegionOverlayContentPolicy.failure(
            currentOriginal = pending.original,
            currentTranslation = pending.translation,
        )
        return JSONObject()
            .put("classification", failure.category.contractName())
            .put("retry", retry)
            .put("maximum_attempts", if (retry) OnlineHttpPolicy.MAX_ATTEMPTS else 1)
            .apply {
                retryDelay?.let { put("minimum_retry_delay_ms", it) }
            }
            .put(
                "preserve_previous_translation",
                pending == previous && failed == previous,
            )
    }

    private fun compareEveryField(
        caseId: String,
        expected: JSONObject,
        actual: JSONObject,
    ) {
        val expectedFields = expected.keys().asSequence().toSet()
        val actualFields = actual.keys().asSequence().toSet()
        assertEquals("$caseId field schema", expectedFields, actualFields)
        expectedFields.forEach { field ->
            val expectedValue = expected.get(field)
            val actualValue = actual.get(field)
            if (expectedValue is Number && actualValue is Number) {
                assertEquals("$caseId.$field", expectedValue.toLong(), actualValue.toLong())
            } else {
                assertEquals("$caseId.$field", expectedValue, actualValue)
            }
        }
    }

    private fun OnlineFailureCategory.contractName(): String = when (this) {
        OnlineFailureCategory.CREDENTIALS -> "credentials"
        OnlineFailureCategory.ENDPOINT_OR_MODEL -> "endpoint_or_model"
        OnlineFailureCategory.RATE_LIMIT -> "rate_limit"
        OnlineFailureCategory.TEMPORARY_SERVICE -> "temporary_service"
        OnlineFailureCategory.REQUEST_CONTRACT -> "request_contract"
        OnlineFailureCategory.SERVER -> "server"
        OnlineFailureCategory.DNS -> "dns"
        OnlineFailureCategory.TLS -> "tls"
        OnlineFailureCategory.TIMEOUT -> "timeout"
        OnlineFailureCategory.NETWORK -> "network"
        OnlineFailureCategory.RESPONSE -> "response"
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of("").toAbsolutePath().normalize()
        while (!Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.parent
                ?: error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return candidate
    }

    private companion object {
        const val PRODUCER_SOURCE =
            "app/src/testOnline/java/com/screentranslation/app/online/" +
                "OnlineFailureContractExecutionTest.kt"
    }
}
