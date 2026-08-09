package com.screentranslation.app.online

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class OnlineFailureEvidenceProtocolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `public expected-copy JSON is not a challenge response`() {
        val expectedCopy = JSONObject()
            .put("schema_version", 2)
            .put("evidence_kind", "kotlin_policy_execution")
            .put("contract_sha256", SHA_A)
            .put("producer", OnlineFailureEvidenceProtocol.PRODUCER)
            .put("producer_source_sha256", SHA_B)
            .put("cases", JSONArray())

        val error = assertThrows(IllegalStateException::class.java) {
            OnlineFailureEvidenceProtocol.parseChallenge(
                challengeBytes = expectedCopy.toString().toByteArray(),
                expectedContractSha256 = SHA_A,
                expectedProducerSourceSha256 = SHA_B,
                expectedExecutionChainSha256 = SHA_D,
                nowEpochMillis = 1_500L,
            )
        }

        assertTrue(error.message.orEmpty().contains("fields differ"))
    }

    @Test
    fun `response binds every production result to the fresh nonce`() {
        val challengeBytes = challengeBytes(nonce = SHA_C)
        val challenge = OnlineFailureEvidenceProtocol.parseChallenge(
            challengeBytes = challengeBytes,
            expectedContractSha256 = SHA_A,
            expectedProducerSourceSha256 = SHA_B,
            expectedExecutionChainSha256 = SHA_D,
            nowEpochMillis = 1_500L,
        )
        val actual = JSONObject()
            .put("classification", "credentials")
            .put("retry", false)
            .put("maximum_attempts", 1)
            .put("preserve_previous_translation", true)
        val evidence = OnlineFailureEvidenceProtocol.buildEvidence(
            challenge = challenge,
            contractSha256 = SHA_A,
            producerSourceSha256 = SHA_B,
            evidenceCases = JSONArray().put(
                JSONObject()
                    .put("case_id", "http_401_invalid_key")
                    .put("actual", actual),
            ),
            generatedAtEpochMillis = 1_600L,
        )

        assertEquals(3, evidence.getInt("schema_version"))
        assertEquals(SHA_C, evidence.getString("challenge_nonce"))
        assertEquals(SHA_D, evidence.getString("execution_chain_sha256"))
        assertEquals(
            OnlineFailureEvidenceProtocol.sha256(challengeBytes),
            evidence.getString("challenge_sha256"),
        )
        assertEquals(
            OnlineFailureEvidenceProtocol.executionSha256(
                SHA_C,
                "http_401_invalid_key",
                actual,
            ),
            evidence.getJSONArray("cases").getJSONObject(0).getString("execution_sha256"),
        )
    }

    @Test
    fun `expired challenge is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            OnlineFailureEvidenceProtocol.parseChallenge(
                challengeBytes = challengeBytes(nonce = SHA_C),
                expectedContractSha256 = SHA_A,
                expectedProducerSourceSha256 = SHA_B,
                expectedExecutionChainSha256 = SHA_D,
                nowEpochMillis = 3_001L,
            )
        }

        assertTrue(error.message.orEmpty().contains("not currently valid"))
    }

    @Test
    fun `formal response never overwrites a planted expected-copy file`() {
        val output = temporaryFolder.root.toPath().resolve("evidence.json")
        Files.writeString(output, "{\"forged\":true}\n")
        val evidence = JSONObject().put("challenge_nonce", SHA_C)

        val error = assertThrows(IllegalStateException::class.java) {
            OnlineFailureEvidenceProtocol.writeNewEvidence(output, evidence)
        }

        assertTrue(error.message.orEmpty().contains("must not already exist"))
        assertEquals("{\"forged\":true}\n", Files.readString(output))
    }

    @Test
    fun `challenge for another execution chain is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            OnlineFailureEvidenceProtocol.parseChallenge(
                challengeBytes = challengeBytes(nonce = SHA_C),
                expectedContractSha256 = SHA_A,
                expectedProducerSourceSha256 = SHA_B,
                expectedExecutionChainSha256 = SHA_A,
                nowEpochMillis = 1_500L,
            )
        }

        assertTrue(error.message.orEmpty().contains("another execution chain"))
    }

    private fun challengeBytes(nonce: String): ByteArray = JSONObject()
        .put("schema_version", 1)
        .put("purpose", OnlineFailureEvidenceProtocol.PURPOSE)
        .put("nonce", nonce)
        .put("issued_at_epoch_ms", 1_000L)
        .put("expires_at_epoch_ms", 3_000L)
        .put("contract_sha256", SHA_A)
        .put("producer_source_sha256", SHA_B)
        .put("execution_chain_sha256", SHA_D)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private companion object {
        const val SHA_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
