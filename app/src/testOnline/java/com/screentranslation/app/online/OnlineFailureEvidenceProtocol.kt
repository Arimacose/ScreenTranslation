package com.screentranslation.app.online

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Challenge/response envelope for formal Online failure-contract evidence.
 *
 * The challenge is created by the gate immediately before it starts Gradle. A
 * response is written to a new, gate-owned path, so a checked-in or caller-
 * supplied JSON file is never a formal input. The response proves freshness
 * for one hash-pinned local checkout; it is not a signature or remote runner
 * attestation.
 */
internal object OnlineFailureEvidenceProtocol {
    const val CHALLENGE_PROPERTY = "screenTranslation.onlineEvidence.challengeFile"
    const val OUTPUT_PROPERTY = "screenTranslation.onlineEvidence.outputFile"
    const val PURPOSE = "online_failure_contract_production_execution"
    const val EVIDENCE_KIND = "kotlin_policy_execution_challenge_response"
    const val PRODUCER = "OnlineFailureContractExecutionTest"
    const val CHALLENGE_SCHEMA_VERSION = 1
    const val EVIDENCE_SCHEMA_VERSION = 3

    val EXECUTION_CHAIN_PATHS: List<String> = listOf(
        "app/build.gradle.kts",
        "app/src/main/java/com/screentranslation/app/overlay/OverlayController.kt",
        "app/src/main/java/com/screentranslation/app/service/ScreenTranslationService.kt",
        "app/src/online/java/com/screentranslation/app/online/OnlineHttpPolicy.kt",
        "app/src/online/java/com/screentranslation/app/online/OpenAiChatProtocol.kt",
        "app/src/testOnline/java/com/screentranslation/app/online/OnlineFailureContractExecutionTest.kt",
        "app/src/testOnline/java/com/screentranslation/app/online/OnlineFailureEvidenceProtocol.kt",
        "build.gradle.kts",
        "gradle.properties",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradlew",
        "gradlew.bat",
        "settings.gradle.kts",
        "tools/model-benchmark/online_failure_evidence.py",
    ).sorted()

    private const val MAX_CHALLENGE_LIFETIME_MILLIS = 15L * 60L * 1_000L
    private val lowercaseSha256 = Regex("[0-9a-f]{64}")

    data class Request(
        val challengePath: Path,
        val outputPath: Path,
    )

    data class Challenge(
        val nonce: String,
        val challengeSha256: String,
        val issuedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val executionChainSha256: String,
    )

    fun requestFromSystemProperties(properties: Map<String, String>): Request? {
        val challengeValue = properties[CHALLENGE_PROPERTY]?.trim().orEmpty()
        val outputValue = properties[OUTPUT_PROPERTY]?.trim().orEmpty()
        if (challengeValue.isEmpty() && outputValue.isEmpty()) return null
        check(challengeValue.isNotEmpty() && outputValue.isNotEmpty()) {
            "Formal Online evidence requires both challenge and output paths"
        }
        val challengePath = Path.of(challengeValue).toAbsolutePath().normalize()
        val outputPath = Path.of(outputValue).toAbsolutePath().normalize()
        check(challengePath != outputPath) {
            "Formal Online evidence challenge and output paths must differ"
        }
        check(Files.isRegularFile(challengePath)) {
            "Formal Online evidence challenge is missing: $challengePath"
        }
        check(!Files.exists(outputPath)) {
            "Formal Online evidence output must not already exist: $outputPath"
        }
        return Request(challengePath, outputPath)
    }

    fun parseChallenge(
        challengeBytes: ByteArray,
        expectedContractSha256: String,
        expectedProducerSourceSha256: String,
        expectedExecutionChainSha256: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Challenge {
        val root = JSONObject(challengeBytes.toString(Charsets.UTF_8))
        requireExactKeys(
            root,
            setOf(
                "schema_version",
                "purpose",
                "nonce",
                "issued_at_epoch_ms",
                "expires_at_epoch_ms",
                "contract_sha256",
                "producer_source_sha256",
                "execution_chain_sha256",
            ),
            "Online evidence challenge",
        )
        check(root.getInt("schema_version") == CHALLENGE_SCHEMA_VERSION) {
            "Unsupported Online evidence challenge schema"
        }
        check(root.getString("purpose") == PURPOSE) {
            "Online evidence challenge has the wrong purpose"
        }
        val nonce = root.getString("nonce")
        check(lowercaseSha256.matches(nonce)) {
            "Online evidence challenge nonce must be 256 random bits"
        }
        val issuedAt = root.getLong("issued_at_epoch_ms")
        val expiresAt = root.getLong("expires_at_epoch_ms")
        check(expiresAt > issuedAt && expiresAt - issuedAt <= MAX_CHALLENGE_LIFETIME_MILLIS) {
            "Online evidence challenge lifetime is invalid"
        }
        check(nowEpochMillis in issuedAt..expiresAt) {
            "Online evidence challenge is not currently valid"
        }
        check(root.getString("contract_sha256") == expectedContractSha256) {
            "Online evidence challenge targets another failure contract"
        }
        check(root.getString("producer_source_sha256") == expectedProducerSourceSha256) {
            "Online evidence challenge targets another producer source"
        }
        check(root.getString("execution_chain_sha256") == expectedExecutionChainSha256) {
            "Online evidence challenge targets another execution chain"
        }
        check(lowercaseSha256.matches(expectedExecutionChainSha256)) {
            "Online evidence execution-chain hash must be SHA-256"
        }
        return Challenge(
            nonce = nonce,
            challengeSha256 = sha256(challengeBytes),
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAt,
            executionChainSha256 = expectedExecutionChainSha256,
        )
    }

    fun buildEvidence(
        challenge: Challenge,
        contractSha256: String,
        producerSourceSha256: String,
        evidenceCases: JSONArray,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): JSONObject {
        check(generatedAtEpochMillis in challenge.issuedAtEpochMillis..challenge.expiresAtEpochMillis) {
            "Online failure evidence was generated outside the challenge window"
        }
        val boundCases = JSONArray()
        for (index in 0 until evidenceCases.length()) {
            val item = evidenceCases.getJSONObject(index)
            requireExactKeys(item, setOf("case_id", "actual"), "Online evidence case")
            val caseId = item.getString("case_id")
            val actual = item.getJSONObject("actual")
            boundCases.put(
                JSONObject()
                    .put("case_id", caseId)
                    .put("actual", actual)
                    .put(
                        "execution_sha256",
                        executionSha256(challenge.nonce, caseId, actual),
                    ),
            )
        }
        return JSONObject()
            .put("schema_version", EVIDENCE_SCHEMA_VERSION)
            .put("evidence_kind", EVIDENCE_KIND)
            .put("challenge_nonce", challenge.nonce)
            .put("challenge_sha256", challenge.challengeSha256)
            .put("generated_at_epoch_ms", generatedAtEpochMillis)
            .put("contract_sha256", contractSha256)
            .put("producer", PRODUCER)
            .put("producer_source_sha256", producerSourceSha256)
            .put("execution_chain_sha256", challenge.executionChainSha256)
            .put("cases", boundCases)
    }

    fun writeNewEvidence(outputPath: Path, evidence: JSONObject) {
        val parent = checkNotNull(outputPath.parent) {
            "Online evidence output must have a parent directory"
        }
        Files.createDirectories(parent)
        check(!Files.exists(outputPath)) {
            "Formal Online evidence output must not already exist: $outputPath"
        }
        val bytes = (evidence.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        Files.write(
            outputPath,
            bytes,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    fun executionSha256(nonce: String, caseId: String, actual: JSONObject): String {
        val payload = "$nonce\n$caseId\n${canonicalJson(actual)}"
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    fun executionChainSha256(repositoryRoot: Path): String {
        val payload = buildString {
            EXECUTION_CHAIN_PATHS.forEach { relative ->
                val source = repositoryRoot.resolve(relative).normalize()
                check(Files.isRegularFile(source)) {
                    "Online evidence execution-chain file is missing: $source"
                }
                append(relative)
                append('\u0000')
                append(sha256(Files.readAllBytes(source)))
                append('\n')
            }
        }
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported JSON value: ${value::class.java.name}")
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>, label: String) {
        val actual = value.keys().asSequence().toSet()
        check(actual == expected) {
            "$label fields differ: expected=$expected actual=$actual"
        }
    }
}
