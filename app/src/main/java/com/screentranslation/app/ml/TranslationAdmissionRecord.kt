package com.screentranslation.app.ml

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable admission result parsed from the repository-generated canonical record.
 *
 * The constructor is private: application code cannot manufacture a trusted runtime
 * or measurement result from a URL, commit-shaped string, or boolean. The only
 * production instance is parsed from [GeneratedTranslationAdmissionEvidence], whose
 * exact JSON and SHA-256 are regenerated and compared by repository CI.
 */
class TranslationProviderAdmission private constructor(
    val candidateId: String,
    val canonicalSha256: String,
    val runtimeGateSatisfied: Boolean,
    failures: Set<MiddleTierAdmissionFailure>,
) {
    val failures: Set<MiddleTierAdmissionFailure> =
        Collections.unmodifiableSet(LinkedHashSet(failures))

    val isSatisfied: Boolean = this.failures.isEmpty()

    companion object {
        internal fun parseCanonicalHyMt2Stq(): TranslationProviderAdmission =
            TranslationAdmissionRecordParser.parse(
                json = GeneratedTranslationAdmissionEvidence.JSON,
                expectedSha256 = GeneratedTranslationAdmissionEvidence.SHA256,
            ).let { parsed ->
                TranslationProviderAdmission(
                    candidateId = parsed.candidateId,
                    canonicalSha256 = parsed.canonicalSha256,
                    runtimeGateSatisfied = parsed.runtimeGateSatisfied,
                    failures = parsed.failures,
                )
            }
    }
}

internal data class ParsedTranslationAdmission(
    val candidateId: String,
    val canonicalSha256: String,
    val runtimeGateSatisfied: Boolean,
    val failures: Set<MiddleTierAdmissionFailure>,
)

internal data class TranslationAdmissionArtifactBindings(
    val corpusVerified: Boolean,
    val sourceModelVerified: Boolean,
    val runnableModelVerified: Boolean,
    val transformationManifestVerified: Boolean,
    val apkVerified: Boolean,
    val signerVerified: Boolean,
    val deviceRomVerified: Boolean,
    val scoreVerifiedByRoute: Map<TranslationRoute, Boolean>,
)

internal object TranslationAdmissionRecordParser {
    private const val CANONICAL_SCHEMA = "screen-translation-admission/v1"
    private const val EXPECTED_CANDIDATE_ID =
        "hymt2-stq-2026-07-30-xiaomi15pro-android16"
    private const val EXPECTED_UPSTREAM_REPOSITORY = "ggml-org/llama.cpp"
    private const val EXPECTED_PULL_REQUEST_NUMBER = 22_836
    private const val EXPECTED_PULL_REQUEST_URL =
        "https://github.com/ggml-org/llama.cpp/pull/22836"
    private const val EXPECTED_PULL_REQUEST_HEAD =
        "7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7"
    private const val EXPECTED_SUBMODULE_PATH = "third_party/llama.cpp"
    private const val EXPECTED_RUNTIME_COMMIT =
        "caa596ab3f0f8768ee326d6e3d5d39782194676c"
    private const val EXPECTED_SOURCE_MODEL_SHA256 =
        "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"
    private const val EXPECTED_SOURCE_MODEL_REVISION =
        "9df5c824a00a744fb0512a29c640466f4d97dfb0"
    private const val EXPECTED_SOURCE_MODEL_SIZE_BYTES = 461_860_800L
    private const val EXPECTED_RUNNABLE_MODEL_SHA256 =
        "e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7"
    private const val EXPECTED_TRANSFORMATION_MANIFEST_SHA256 =
        "b4713fdb0e95c74446688597d7c0bab6cc217379379115dc535c2e89f599f284"
    private const val EXPECTED_CORPUS_ID = "2026.08-public-v2-original-references"
    private const val EXPECTED_CORPUS_SHA256 =
        "043bb49a27d647a24aba96c605f8d5eea0b5fd8d19eac490161b4e48b772bd72"
    private const val EXPECTED_SOURCE_PATH =
        "docs/evidence/hymt2-stq-admission-source-v1.json"
    private const val EXPECTED_VERIFIER_PATH =
        "tools/provider-admission/verify_translation_admission.py"

    fun parse(json: String, expectedSha256: String): ParsedTranslationAdmission {
        requireSha256(expectedSha256, "canonical expected SHA-256")
        val actualSha256 = sha256(json)
        require(actualSha256 == expectedSha256) {
            "Canonical admission JSON SHA-256 mismatch"
        }
        val root = JSONObject(json).requireExactKeys(
            "schema",
            "candidate_id",
            "recorded_date",
            "source_record",
            "verifier",
            "runtime_gate",
            "bindings",
            "routes",
            "integrated_release",
            "policy",
            "evaluation",
        )
        require(root.requireString("schema") == CANONICAL_SCHEMA)
        val candidateId = root.requireString("candidate_id")
        require(candidateId == EXPECTED_CANDIDATE_ID)
        root.requireString("recorded_date")
        parsePinnedRepositoryRecord(root.requireObject("source_record"), EXPECTED_SOURCE_PATH)
        parsePinnedRepositoryRecord(root.requireObject("verifier"), EXPECTED_VERIFIER_PATH)

        val runtimeGateSatisfied = parseRuntimeGate(root.requireObject("runtime_gate"))
        val baseBindings = parseBindings(root.requireObject("bindings"))
        val routes = parseRoutes(root.requireArray("routes"))
        val integratedRelease = parseIntegratedRelease(root.requireObject("integrated_release"))
        require(routes.map { it.measurement.route } == listOf(
            TranslationRoute("en", "zh"),
            TranslationRoute("ja", "zh"),
        )) {
            "Canonical routes must be exactly ordered as en-zh, ja-zh"
        }
        require(routes.all { it.scoreVerified } == integratedRelease.summaryVerified) {
            "Route score summaries and integrated Release summary form one admission bundle"
        }
        if (integratedRelease.summaryVerified) {
            require(routes.map { it.evaluationRunId }.distinct() == listOf(
                integratedRelease.evaluationRunId,
            )) {
                "Route and Release summaries must share one evaluation run ID"
            }
            require(
                routes.associate { it.routeId to it.actualScoreSummarySha256 } ==
                    integratedRelease.scoreSummarySha256ByRoute,
            ) {
                "Release summary must pin both route score summary hashes"
            }
        }
        val policy = parsePolicy(root.requireObject("policy"))
        require(policy == MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER) {
            "Canonical admission policy differs from the published application policy"
        }
        val measurement = MiddleTierCandidateMeasurement(
            routeMeasurements = routes.map { it.measurement },
            integratedRelease = integratedRelease.measurement,
        )
        val bindings = TranslationAdmissionArtifactBindings(
            corpusVerified = baseBindings.corpusVerified,
            sourceModelVerified = baseBindings.sourceModelVerified,
            runnableModelVerified = baseBindings.runnableModelVerified,
            transformationManifestVerified = baseBindings.transformationManifestVerified,
            apkVerified = baseBindings.apkVerified,
            signerVerified = baseBindings.signerVerified,
            deviceRomVerified = baseBindings.deviceRomVerified,
            scoreVerifiedByRoute = routes.associate { it.measurement.route to it.scoreVerified },
        )
        val computedFailures = policy.evaluate(
            measurement = measurement,
            runtimeGateSatisfied = runtimeGateSatisfied,
            bindings = bindings,
            integratedSummaryVerified = integratedRelease.summaryVerified,
        )
        val evaluation = root.requireObject("evaluation").requireExactKeys(
            "failures",
            "satisfied",
        )
        val recordedFailures = evaluation.requireArray("failures")
            .requireUniqueStringList("evaluation.failures")
            .map { MiddleTierAdmissionFailure.valueOf(it) }
        require(recordedFailures == computedFailures.toList()) {
            "Canonical admission failure list does not match application policy evaluation"
        }
        require(evaluation.requireBoolean("satisfied") == computedFailures.isEmpty()) {
            "Canonical admission satisfied flag does not match computed failures"
        }
        return ParsedTranslationAdmission(
            candidateId = candidateId,
            canonicalSha256 = actualSha256,
            runtimeGateSatisfied = runtimeGateSatisfied,
            failures = computedFailures,
        )
    }

    private fun parsePinnedRepositoryRecord(value: JSONObject, expectedPath: String) {
        value.requireExactKeys("path", "sha256")
        require(value.requireString("path") == expectedPath)
        requireSha256(value.requireString("sha256"), "$expectedPath SHA-256")
    }

    private fun parseRuntimeGate(value: JSONObject): Boolean {
        value.requireExactKeys(
            "upstream_repository",
            "pull_request_number",
            "pull_request_url",
            "pull_request_state",
            "pull_request_updated_at",
            "expected_pull_request_head",
            "observed_pull_request_head",
            "merge_commit",
            "submodule_path",
            "declared_runtime_commit",
            "repository_gitlink_commit",
            "checked_out_submodule_commit",
            "merge_ancestor_of_runtime",
            "runnable_model_expected_sha256",
            "runnable_model_actual_sha256",
            "runnable_model_verified",
            "manifest_expected_sha256",
            "manifest_actual_sha256",
            "manifest_verified",
            "satisfied",
        )
        require(value.requireString("upstream_repository") == EXPECTED_UPSTREAM_REPOSITORY)
        require(value.requireInt("pull_request_number") == EXPECTED_PULL_REQUEST_NUMBER)
        require(value.requireString("pull_request_url") == EXPECTED_PULL_REQUEST_URL)
        val state = value.requireString("pull_request_state")
        require(state in setOf("OPEN", "MERGED", "CLOSED_UNMERGED"))
        value.requireString("pull_request_updated_at")
        val expectedHead = value.requireGitSha("expected_pull_request_head")
        val observedHead = value.requireGitSha("observed_pull_request_head")
        require(expectedHead == EXPECTED_PULL_REQUEST_HEAD)
        val mergeCommit = value.nullableGitSha("merge_commit")
        require(value.requireString("submodule_path") == EXPECTED_SUBMODULE_PATH)
        val declaredRuntime = value.requireGitSha("declared_runtime_commit")
        val gitlink = value.requireGitSha("repository_gitlink_commit")
        require(declaredRuntime == EXPECTED_RUNTIME_COMMIT)
        require(gitlink == EXPECTED_RUNTIME_COMMIT)
        val checkedOut = value.nullableGitSha("checked_out_submodule_commit")
        val ancestor = value.nullableBoolean("merge_ancestor_of_runtime")
        require(checkedOut == EXPECTED_RUNTIME_COMMIT)
        if (state != "MERGED") {
            require(mergeCommit == null && ancestor == null) {
                "An unmerged pull request has no canonical merge or ancestry evidence"
            }
        }
        val runnableExpected = value.requireSha256("runnable_model_expected_sha256")
        require(runnableExpected == EXPECTED_RUNNABLE_MODEL_SHA256)
        val runnableActual = value.nullableSha256("runnable_model_actual_sha256")
        val runnableVerified = runnableActual != null && runnableActual == runnableExpected
        require(value.requireBoolean("runnable_model_verified") == runnableVerified)
        val manifestExpected = value.requireSha256("manifest_expected_sha256")
        require(manifestExpected == EXPECTED_TRANSFORMATION_MANIFEST_SHA256)
        val manifestActual = value.nullableSha256("manifest_actual_sha256")
        val manifestVerified = manifestActual != null && manifestActual == manifestExpected
        require(value.requireBoolean("manifest_verified") == manifestVerified)
        val computed = state == "MERGED" &&
            mergeCommit != null &&
            expectedHead == observedHead &&
            declaredRuntime == gitlink &&
            checkedOut == gitlink &&
            ancestor == true &&
            runnableVerified &&
            manifestVerified
        require(value.requireBoolean("satisfied") == computed) {
            "Canonical runtime satisfied flag does not match the parsed repository evidence"
        }
        return computed
    }

    private data class BaseBindings(
        val corpusVerified: Boolean,
        val sourceModelVerified: Boolean,
        val runnableModelVerified: Boolean,
        val transformationManifestVerified: Boolean,
        val apkVerified: Boolean,
        val signerVerified: Boolean,
        val deviceRomVerified: Boolean,
    )

    private fun parseBindings(value: JSONObject): BaseBindings {
        value.requireExactKeys("corpus", "candidate", "apk", "device")
        val corpus = value.requireObject("corpus").requireExactKeys(
            "corpus_id",
            "path",
            "expected_sha256",
            "actual_sha256",
            "expected_suite_ids",
            "actual_suite_ids",
            "expected_suite_case_counts",
            "actual_suite_case_counts",
            "verified",
        )
        require(corpus.requireString("corpus_id") == EXPECTED_CORPUS_ID)
        require(corpus.requireString("path") == "app/src/benchmark/assets/translation-fixtures.json")
        val corpusExpected = corpus.requireSha256("expected_sha256")
        require(corpusExpected == EXPECTED_CORPUS_SHA256)
        val corpusActual = corpus.requireSha256("actual_sha256")
        val expectedSuites = corpus.requireArray("expected_suite_ids")
            .requireUniqueStringList("bindings.corpus.expected_suite_ids")
        val actualSuites = corpus.requireArray("actual_suite_ids")
            .requireUniqueStringList("bindings.corpus.actual_suite_ids")
        val canonicalSuites = listOf("en-zh-diverse-v2", "ja-zh-diverse-v1")
        require(expectedSuites == canonicalSuites)
        val expectedCaseCountsObject = corpus.requireObject("expected_suite_case_counts")
            .requireExactKeys(*canonicalSuites.toTypedArray())
        val actualCaseCountsObject = corpus.requireObject("actual_suite_case_counts")
            .requireExactKeys(*canonicalSuites.toTypedArray())
        val expectedCaseCounts = canonicalSuites.associateWith { suiteId ->
            expectedCaseCountsObject.requireInt(suiteId)
        }
        val actualCaseCounts = canonicalSuites.associateWith { suiteId ->
            actualCaseCountsObject.requireInt(suiteId)
        }
        require(expectedCaseCounts == canonicalSuites.associateWith { 48 })
        val corpusVerified = corpusExpected == corpusActual &&
            expectedSuites == actualSuites &&
            expectedCaseCounts == actualCaseCounts
        require(corpus.requireBoolean("verified") == corpusVerified)

        val candidate = value.requireObject("candidate").requireExactKeys(
            "source_model",
            "runnable_model",
            "transformation_manifest",
        )
        val sourceModelVerified = parseArtifact(
            candidate.requireObject("source_model"),
            extraKeys = setOf("revision", "expected_size_bytes"),
            expectedSha256 = EXPECTED_SOURCE_MODEL_SHA256,
            expectedRevision = EXPECTED_SOURCE_MODEL_REVISION,
            expectedSizeBytes = EXPECTED_SOURCE_MODEL_SIZE_BYTES,
        )
        val runnableModelVerified = parseArtifact(
            candidate.requireObject("runnable_model"),
            expectedSha256 = EXPECTED_RUNNABLE_MODEL_SHA256,
        )
        val manifestVerified = parseArtifact(
            candidate.requireObject("transformation_manifest"),
            extraKeys = setOf("transformation_id"),
            expectedSha256 = EXPECTED_TRANSFORMATION_MANIFEST_SHA256,
        )
        require(
            candidate.requireObject("transformation_manifest")
                .requireString("transformation_id") == "retag-legacy-stq-gguf-v1",
        )

        val apk = value.requireObject("apk").requireExactKeys(
            "edition",
            "expected_application_id",
            "actual_application_id",
            "expected_sha256",
            "actual_sha256",
            "expected_signer_cert_sha256",
            "actual_signer_cert_sha256",
            "apk_verified",
            "signer_verified",
        )
        require(apk.requireString("edition") == "full")
        val expectedApplicationId = apk.requireString("expected_application_id")
        require(expectedApplicationId == "com.screentranslation.app.full")
        val actualApplicationId = apk.nullableString("actual_application_id")
        val apkExpected = apk.nullableSha256("expected_sha256")
        val apkActual = apk.nullableSha256("actual_sha256")
        val signerExpected = apk.nullableSha256("expected_signer_cert_sha256")
        val signerActual = apk.nullableSha256("actual_signer_cert_sha256")
        val apkVerified = apkExpected != null &&
            apkActual == apkExpected &&
            actualApplicationId == expectedApplicationId
        val signerVerified = signerExpected != null && signerActual == signerExpected
        require(apk.requireBoolean("apk_verified") == apkVerified)
        require(apk.requireBoolean("signer_verified") == signerVerified)

        val device = value.requireObject("device").requireExactKeys(
            "declared",
            "expected_summary_sha256",
            "actual_summary_sha256",
            "verified",
        )
        parseDeclaredDevice(device.requireObject("declared"))
        val deviceExpected = device.nullableSha256("expected_summary_sha256")
        val deviceActual = device.nullableSha256("actual_summary_sha256")
        val deviceVerified = deviceExpected != null && deviceActual == deviceExpected
        require(device.requireBoolean("verified") == deviceVerified)
        return BaseBindings(
            corpusVerified = corpusVerified,
            sourceModelVerified = sourceModelVerified,
            runnableModelVerified = runnableModelVerified,
            transformationManifestVerified = manifestVerified,
            apkVerified = apkVerified,
            signerVerified = signerVerified,
            deviceRomVerified = deviceVerified,
        )
    }

    private fun parseArtifact(
        value: JSONObject,
        extraKeys: Set<String> = emptySet(),
        expectedSha256: String,
        expectedRevision: String? = null,
        expectedSizeBytes: Long? = null,
    ): Boolean {
        value.requireExactKeys(
            *(setOf("expected_sha256", "actual_sha256", "actual_size_bytes", "verified") +
                extraKeys).toTypedArray(),
        )
        val expected = value.requireSha256("expected_sha256")
        require(expected == expectedSha256)
        val actual = value.nullableSha256("actual_sha256")
        val size = value.nullableLong("actual_size_bytes")
        require((actual == null) == (size == null)) {
            "Artifact hash and size must be observed together"
        }
        if (size != null) require(size > 0L)
        if ("revision" in extraKeys) {
            require(value.requireGitSha("revision") == expectedRevision)
        }
        if ("expected_size_bytes" in extraKeys) {
            require(value.requirePositiveLong("expected_size_bytes") == expectedSizeBytes)
        }
        if ("transformation_id" in extraKeys) value.requireString("transformation_id")
        val sizeMatches = if (expectedSizeBytes != null && size != null) {
            size == expectedSizeBytes
        } else {
            true
        }
        val verified = actual != null && actual == expected && sizeMatches
        require(value.requireBoolean("verified") == verified)
        return verified
    }

    private fun parseDeclaredDevice(value: JSONObject) {
        value.requireExactKeys(
            "model",
            "model_code",
            "codename",
            "android_api",
            "android_build",
            "rom",
            "abi",
            "execution",
        )
        require(value.requireString("model") == "Xiaomi 15 Pro")
        require(value.requireString("model_code") == "2410DPN6CC")
        require(value.requireString("codename") == "haotian")
        require(value.requireInt("android_api") == 36)
        require(value.requireString("android_build") == "BP2A.250605.031.A3")
        require(value.requireString("rom") == "HyperOS OS3.0.304.0.WOBCNXM")
        require(value.requireString("abi") == "arm64-v8a")
        require(value.requireString("execution") == "CPU-only")
    }

    private data class ParsedRoute(
        val routeId: String,
        val measurement: MiddleTierRouteMeasurement,
        val scoreVerified: Boolean,
        val actualScoreSummarySha256: String?,
        val evaluationRunId: String?,
    )

    private fun parseRoutes(value: JSONArray): List<ParsedRoute> =
        (0 until value.length()).map { index ->
            val route = value.requireObject(index).requireExactKeys(
                "route_id",
                "corpus_suite_id",
                "expected_critical_check_count",
                "expected_critical_check_ids",
                "historical_artifacts",
                "expected_score_summary_sha256",
                "actual_score_summary_sha256",
                "evaluation_run_id",
                "score_verified",
                "q4_bleu_retention_percent",
                "critical_evaluated_ids",
                "critical_regressed_ids",
                "raw_median_latency_ms",
                "pipeline",
            )
            val routeId = route.requireString("route_id")
            val translationRoute = routeId.toTranslationRoute()
            val corpusSuiteId = route.requireString("corpus_suite_id")
            require(corpusSuiteId == when (routeId) {
                "en-zh" -> "en-zh-diverse-v2"
                "ja-zh" -> "ja-zh-diverse-v1"
                else -> error("Unexpected canonical route: $routeId")
            })
            val expectedCriticalCount = route.requireInt("expected_critical_check_count")
            require(expectedCriticalCount == if (routeId == "en-zh") 64 else 62)
            val expectedCriticalIds = route.requireArray("expected_critical_check_ids")
                .requireUniqueStringList("routes.$routeId.expected_critical_check_ids")
            require(expectedCriticalIds.size == expectedCriticalCount)
            require(expectedCriticalIds.all { it.matches(Regex("[a-z0-9][a-z0-9._-]*")) })
            val historical = route.requireObject("historical_artifacts").requireExactKeys(
                "raw_result_sha256",
                "score_sha256",
            )
            historical.requireSha256("raw_result_sha256")
            historical.requireSha256("score_sha256")
            val scoreExpected = route.nullableSha256("expected_score_summary_sha256")
            val scoreActual = route.nullableSha256("actual_score_summary_sha256")
            val scoreHashVerified = scoreExpected != null && scoreActual == scoreExpected
            val scoreVerified = route.requireBoolean("score_verified")
            require(!scoreVerified || scoreHashVerified)
            val evaluationRunId = route.nullableIdentifier("evaluation_run_id")
            val quality = route.nullableDouble(
                "q4_bleu_retention_percent",
                maximum = 100.0,
            )
            val evaluated = route.nullableUniqueStringList("critical_evaluated_ids")
            val regressed = route.nullableUniqueStringList("critical_regressed_ids")
            if (evaluated != null) require(evaluated == expectedCriticalIds)
            if (regressed != null) require(evaluated != null && regressed.all(evaluated::contains))
            val raw = route.nullableDouble("raw_median_latency_ms")
            val pipelineObject = route.requireObject("pipeline").requireExactKeys(
                "median_latency_ms",
                "p95_latency_ms",
                "timeout_count",
            )
            val pipeline = MiddleTierAppPipelineMeasurement(
                medianLatencyMillis = pipelineObject.nullableDouble("median_latency_ms"),
                p95LatencyMillis = pipelineObject.nullableDouble("p95_latency_ms"),
                timeoutCount = pipelineObject.nullableInt("timeout_count"),
            )
            if (scoreVerified) {
                require(quality != null && evaluated != null && regressed != null && raw != null)
                require(pipeline.isComplete)
                require(evaluationRunId != null)
            } else {
                require(quality == null && evaluated == null && regressed == null && raw == null)
                require(!pipeline.hasAnyMeasurement)
                require(evaluationRunId == null)
            }
            ParsedRoute(
                routeId = routeId,
                measurement = MiddleTierRouteMeasurement(
                    route = translationRoute,
                    q4BleuRetentionPercent = quality,
                    criticalEvaluatedIds = evaluated?.toSet(),
                    criticalRegressedIds = regressed?.toSet(),
                    rawMedianLatencyMillis = raw,
                    appPipeline = pipeline,
                ),
                scoreVerified = scoreVerified,
                actualScoreSummarySha256 = scoreActual,
                evaluationRunId = evaluationRunId,
            )
        }

    private data class ParsedIntegratedRelease(
        val measurement: MiddleTierIntegratedReleaseMeasurement,
        val summaryVerified: Boolean,
        val evaluationRunId: String?,
        val scoreSummarySha256ByRoute: Map<String, String?>?,
    )

    private fun parseIntegratedRelease(value: JSONObject): ParsedIntegratedRelease {
        value.requireExactKeys(
            "expected_summary_sha256",
            "actual_summary_sha256",
            "summary_verified",
            "evaluation_run_id",
            "score_summary_sha256_by_route",
            "process_pss_bytes",
            "process_high_water_bytes",
            "lmk_event_count",
            "thermal",
        )
        val expected = value.nullableSha256("expected_summary_sha256")
        val actual = value.nullableSha256("actual_summary_sha256")
        val hashVerified = expected != null && actual == expected
        val verified = value.requireBoolean("summary_verified")
        require(!verified || hashVerified)
        val evaluationRunId = value.nullableIdentifier("evaluation_run_id")
        val routeScoreHashes = value.nullableSha256Map(
            "score_summary_sha256_by_route",
            setOf("en-zh", "ja-zh"),
        )
        val thermal = value.requireObject("thermal").requireExactKeys(
            "sustained_hot_run_minutes",
            "sample_interval_seconds",
            "samples",
        )
        val measurement = MiddleTierIntegratedReleaseMeasurement(
            processPssBytes = value.nullableLong("process_pss_bytes"),
            processHighWaterBytes = value.nullableLong("process_high_water_bytes"),
            lmkEventCount = value.nullableInt("lmk_event_count"),
            sustainedHotRunMinutes = thermal.nullableDouble("sustained_hot_run_minutes"),
            thermalSampleIntervalSeconds = thermal.nullableDouble("sample_interval_seconds"),
            thermalStatusSamples = thermal.nullableIntList("samples"),
        )
        if (verified) {
            require(measurement.isComplete)
            require(evaluationRunId != null && routeScoreHashes != null)
        } else {
            require(!measurement.hasAnyMeasurement)
            require(evaluationRunId == null && routeScoreHashes == null)
        }
        return ParsedIntegratedRelease(
            measurement = measurement,
            summaryVerified = verified,
            evaluationRunId = evaluationRunId,
            scoreSummarySha256ByRoute = routeScoreHashes,
        )
    }

    private fun parsePolicy(value: JSONObject): MiddleTierAdmissionPolicy {
        value.requireExactKeys(
            "required_route_ids",
            "minimum_q4_bleu_retention_percent",
            "maximum_critical_regressions",
            "maximum_raw_median_latency_ms_exclusive",
            "maximum_pipeline_median_latency_ms_exclusive",
            "maximum_pipeline_p95_latency_ms_exclusive",
            "maximum_pipeline_timeout_count",
            "maximum_process_pss_bytes_exclusive",
            "maximum_process_high_water_bytes_exclusive",
            "maximum_lmk_event_count",
            "minimum_sustained_hot_run_minutes",
            "minimum_thermal_sample_count",
            "maximum_thermal_sample_interval_seconds",
            "maximum_thermal_status",
        )
        return MiddleTierAdmissionPolicy(
            requiredRoutes = value.requireArray("required_route_ids")
                .requireUniqueStringList("policy.required_route_ids")
                .also { require(it == listOf("en-zh", "ja-zh")) }
                .mapTo(linkedSetOf()) { it.toTranslationRoute() },
            minimumQ4BleuRetentionPercent = value.requireDouble(
                "minimum_q4_bleu_retention_percent",
                maximum = 100.0,
            ),
            maximumCriticalCheckRegressionsAgainstShippingLite =
                value.requireInt("maximum_critical_regressions"),
            maximumRawMedianLatencyMillisExclusive =
                value.requireDouble("maximum_raw_median_latency_ms_exclusive"),
            maximumAppPipelineMedianLatencyMillisExclusive =
                value.requireDouble("maximum_pipeline_median_latency_ms_exclusive"),
            maximumAppPipelineP95LatencyMillisExclusive =
                value.requireDouble("maximum_pipeline_p95_latency_ms_exclusive"),
            maximumAppPipelineTimeoutCount = value.requireInt("maximum_pipeline_timeout_count"),
            maximumIntegratedProcessPssBytesExclusive =
                value.requireLong("maximum_process_pss_bytes_exclusive"),
            maximumIntegratedProcessHighWaterBytesExclusive =
                value.requireLong("maximum_process_high_water_bytes_exclusive"),
            maximumLmkEventCount = value.requireInt("maximum_lmk_event_count"),
            minimumSustainedHotRunMinutes =
                value.requireDouble("minimum_sustained_hot_run_minutes"),
            minimumThermalStatusSampleCount = value.requireInt("minimum_thermal_sample_count"),
            maximumThermalSampleIntervalSeconds =
                value.requireDouble("maximum_thermal_sample_interval_seconds"),
            maximumThermalStatus = value.requireInt("maximum_thermal_status"),
        )
    }

    private fun String.toTranslationRoute(): TranslationRoute {
        val parts = split('-')
        require(parts.size == 2 && parts.all { it.length == 2 }) {
            "Invalid canonical route ID: $this"
        }
        return TranslationRoute(parts[0], parts[1])
    }

    private fun JSONObject.requireExactKeys(vararg expected: String): JSONObject {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) {
            "JSON schema mismatch: missing=${expected.toSet() - actual}, extra=${actual - expected.toSet()}"
        }
        return this
    }

    private fun JSONObject.requireObject(name: String): JSONObject =
        get(name).let { require(it is JSONObject) { "$name must be an object" }; it }

    private fun JSONObject.requireArray(name: String): JSONArray =
        get(name).let { require(it is JSONArray) { "$name must be an array" }; it }

    private fun JSONObject.requireString(name: String): String =
        get(name).let { require(it is String && it.isNotBlank()) { "$name must be a string" }; it }

    private fun JSONObject.nullableString(name: String): String? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is String -> value.also { require(it.isNotBlank()) }
        else -> error("$name must be a string or null")
    }

    private fun JSONObject.requireBoolean(name: String): Boolean =
        get(name).let { require(it is Boolean) { "$name must be a boolean" }; it }

    private fun JSONObject.nullableBoolean(name: String): Boolean? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is Boolean -> value
        else -> error("$name must be a boolean or null")
    }

    private fun JSONObject.requireInt(name: String): Int = requireLong(name).also {
        require(it in 0..Int.MAX_VALUE.toLong())
    }.toInt()

    private fun JSONObject.nullableInt(name: String): Int? = nullableLong(name)?.also {
        require(it in 0..Int.MAX_VALUE.toLong())
    }?.toInt()

    private fun JSONObject.requireLong(name: String): Long =
        get(name).toIntegralLong(name).also { require(it >= 0L) }

    private fun JSONObject.requirePositiveLong(name: String): Long = requireLong(name).also {
        require(it > 0L)
    }

    private fun JSONObject.nullableLong(name: String): Long? = when (val value = get(name)) {
        JSONObject.NULL -> null
        else -> value.toIntegralLong(name).also { require(it >= 0L) }
    }

    private fun Any.toIntegralLong(name: String): Long = when (this) {
        is Byte, is Short, is Int, is Long -> (this as Number).toLong()
        is BigInteger -> try {
            longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$name is outside the signed 64-bit range")
        }
        else -> throw IllegalArgumentException("$name must be an integer")
    }

    private fun JSONObject.requireDouble(name: String, maximum: Double? = null): Double =
        get(name).toFiniteDouble(name, maximum)

    private fun JSONObject.nullableDouble(name: String, maximum: Double? = null): Double? =
        when (val value = get(name)) {
            JSONObject.NULL -> null
            else -> value.toFiniteDouble(name, maximum)
        }

    private fun Any.toFiniteDouble(name: String, maximum: Double?): Double {
        require(this is Number) { "$name must be a number" }
        val result = toDouble()
        require(result.isFinite() && result >= 0.0) { "$name must be finite and non-negative" }
        require(maximum == null || result <= maximum) { "$name must be <= $maximum" }
        return result
    }

    private fun JSONObject.requireGitSha(name: String): String = requireString(name).also {
        require(it.matches(Regex("[0-9a-f]{40}"))) { "$name must be a full lowercase Git SHA" }
    }

    private fun JSONObject.nullableGitSha(name: String): String? = nullableString(name)?.also {
        require(it.matches(Regex("[0-9a-f]{40}"))) { "$name must be a full lowercase Git SHA" }
    }

    private fun JSONObject.requireSha256(name: String): String = requireString(name).also {
        requireSha256(it, name)
    }

    private fun JSONObject.nullableSha256(name: String): String? = nullableString(name)?.also {
        requireSha256(it, name)
    }

    private fun JSONArray.requireObject(index: Int): JSONObject =
        get(index).let { require(it is JSONObject) { "array[$index] must be an object" }; it }

    private fun JSONArray.requireUniqueStringList(context: String): List<String> {
        val values = (0 until length()).map { index ->
            get(index).let {
                require(it is String && it.isNotBlank()) { "$context[$index] must be a string" }
                it
            }
        }
        require(values.size == values.toSet().size) { "$context contains duplicates" }
        return values
    }

    private fun JSONObject.nullableUniqueStringList(name: String): List<String>? =
        when (val value = get(name)) {
            JSONObject.NULL -> null
            is JSONArray -> value.requireUniqueStringList(name)
            else -> error("$name must be an array or null")
        }

    private fun JSONObject.nullableIdentifier(name: String): String? = nullableString(name)?.also {
        require(it.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "$name must be an identifier" }
    }

    private fun JSONObject.nullableSha256Map(
        name: String,
        expectedKeys: Set<String>,
    ): Map<String, String?>? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is JSONObject -> {
            value.requireExactKeys(*expectedKeys.toTypedArray())
            expectedKeys.associateWith { key -> value.nullableSha256(key) }
                .also { entries -> require(entries.values.all { it != null }) }
        }
        else -> error("$name must be an object or null")
    }

    private fun JSONObject.nullableIntList(name: String): List<Int>? =
        when (val value = get(name)) {
            JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).map { index ->
                value.get(index).toIntegralLong("$name[$index]").also {
                    require(it in 0..Int.MAX_VALUE.toLong())
                }.toInt()
            }
            else -> error("$name must be an integer array or null")
        }

    private fun requireSha256(value: String, context: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "$context must be a lowercase SHA-256"
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
