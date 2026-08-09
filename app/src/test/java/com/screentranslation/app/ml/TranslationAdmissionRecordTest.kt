package com.screentranslation.app.ml

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAdmissionRecordTest {
    @Test
    fun generatedCanonicalRecordParsesButRemainsBlocked() {
        val admission = TranslationAdmissionRecordParser.parse(
            GeneratedTranslationAdmissionEvidence.JSON,
            GeneratedTranslationAdmissionEvidence.SHA256,
        )

        assertFalse(admission.runtimeGateSatisfied)
        assertTrue(admission.failures.isNotEmpty())
        assertTrue(MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED in admission.failures)
        assertTrue(MiddleTierAdmissionFailure.QUALITY_MEASUREMENT_MISSING in admission.failures)
    }

    @Test(expected = IllegalArgumentException::class)
    fun editedJsonWithOriginalPinIsRejected() {
        val edited = GeneratedTranslationAdmissionEvidence.JSON.replace(
            "ggml-org/llama.cpp",
            "fictional-org/fictional-runtime",
        )

        TranslationAdmissionRecordParser.parse(
            edited,
            GeneratedTranslationAdmissionEvidence.SHA256,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun fictionalPullRequestUrlIsRejectedEvenWithRecomputedPin() {
        val root = canonicalObject()
        root.getJSONObject("runtime_gate").put(
            "pull_request_url",
            "https://example.invalid/fictional/pull/22836",
        )
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fictionalCommitIsRejectedEvenWhenCallerMakesExpectedAndObservedMatch() {
        val root = canonicalObject()
        val gate = root.getJSONObject("runtime_gate")
        val fictional = "a".repeat(40)
        gate.put("expected_pull_request_head", fictional)
        gate.put("observed_pull_request_head", fictional)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun callerSuppliedAncestorBooleanOutsideSchemaIsRejected() {
        val root = canonicalObject()
        root.getJSONObject("runtime_gate").put("caller_verified_ancestor", true)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun callerCannotTurnOpenPullRequestAncestryIntoTrue() {
        val root = canonicalObject()
        root.getJSONObject("runtime_gate").put("merge_ancestor_of_runtime", true)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun forgedSatisfiedBooleanIsRecomputedAndRejected() {
        val root = canonicalObject()
        root.getJSONObject("runtime_gate").put("satisfied", true)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun notMeasuredStringIsNotAcceptedAsNull() {
        val root = canonicalObject()
        root.getJSONArray("routes").getJSONObject(0)
            .put("q4_bleu_retention_percent", "NOT_MEASURED")
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bleuAboveOneHundredIsRejected() {
        val root = canonicalObject()
        root.getJSONArray("routes").getJSONObject(0)
            .put("q4_bleu_retention_percent", 100.001)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun observedArtifactHashWithoutObservedSizeIsRejected() {
        val root = canonicalObject()
        val source = root.getJSONObject("bindings")
            .getJSONObject("candidate")
            .getJSONObject("source_model")
        source.put("actual_sha256", source.getString("expected_sha256"))
        source.put("verified", false)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun canonicalFailureOrderIsStrict() {
        val root = canonicalObject()
        val evaluation = root.getJSONObject("evaluation")
        val failures = evaluation.getJSONArray("failures")
        val first = failures.getString(0)
        failures.put(0, failures.getString(1))
        failures.put(1, first)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRouteIsRejectedByRecordedFailureRecomputation() {
        val root = canonicalObject()
        val routes = root.getJSONArray("routes")
        routes.put(JSONObject(routes.getJSONObject(0).toString()))
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleCorpusCaseCountIsRejectedEvenWithRecomputedPin() {
        val root = canonicalObject()
        root.getJSONObject("bindings")
            .getJSONObject("corpus")
            .getJSONObject("actual_suite_case_counts")
            .put("en-zh-diverse-v2", 47)
        parseWithRecomputedPin(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleCriticalCheckCountIsRejectedEvenWithRecomputedPin() {
        val root = canonicalObject()
        root.getJSONArray("routes").getJSONObject(0)
            .put("expected_critical_check_count", 54)
        parseWithRecomputedPin(root)
    }

    private fun canonicalObject(): JSONObject =
        JSONObject(GeneratedTranslationAdmissionEvidence.JSON)

    private fun parseWithRecomputedPin(root: JSONObject): ParsedTranslationAdmission {
        val json = root.toString()
        return TranslationAdmissionRecordParser.parse(json, sha256(json))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
