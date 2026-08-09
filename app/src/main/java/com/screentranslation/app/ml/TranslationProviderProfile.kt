package com.screentranslation.app.ml

import java.util.Locale

enum class TranslationProviderId {
    BERGAMOT_LITE,
    HY_MT2_Q4_FULL,
    ONLINE_BYOK,
    ML_KIT_BENCHMARK,
    HY_MT2_STQ_CANDIDATE,
}

enum class TranslationProviderAvailability {
    SHIPPING,
    EXPERIMENTAL,
    BENCHMARK_ONLY,
    EVALUATION_BLOCKED,
}

data class TranslationRoute(
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
    val pivotLanguageTags: List<String> = emptyList(),
) {
    init {
        require(sourceLanguageTag == normalizeLanguageTag(sourceLanguageTag)) {
            "Source language tag must be normalized"
        }
        require(targetLanguageTag == normalizeLanguageTag(targetLanguageTag)) {
            "Target language tag must be normalized"
        }
        require(sourceLanguageTag != targetLanguageTag) {
            "Source and target language tags must differ"
        }
        require(pivotLanguageTags.all { it == normalizeLanguageTag(it) }) {
            "Pivot language tags must be normalized"
        }
    }
}

sealed interface TranslationLanguageCapability {
    fun routeFor(sourceLanguageTag: String, targetLanguageTag: String): TranslationRoute?

    data class ExplicitRoutes(
        val routes: Set<TranslationRoute>,
    ) : TranslationLanguageCapability {
        init {
            require(routes.isNotEmpty()) { "At least one language route is required" }
        }

        override fun routeFor(
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): TranslationRoute? {
            val source = normalizeLanguageTag(sourceLanguageTag)
            val target = normalizeLanguageTag(targetLanguageTag)
            return routes.firstOrNull {
                it.sourceLanguageTag == source && it.targetLanguageTag == target
            }
        }
    }

    data class AnySourceToTargets(
        val targetLanguageTags: Set<String>,
    ) : TranslationLanguageCapability {
        init {
            require(targetLanguageTags.isNotEmpty()) { "At least one target is required" }
            require(targetLanguageTags.all { it == normalizeLanguageTag(it) }) {
                "Target language tags must be normalized"
            }
        }

        override fun routeFor(
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): TranslationRoute? {
            val source = normalizeLanguageTag(sourceLanguageTag)
            val target = normalizeLanguageTag(targetLanguageTag)
            if (source.isBlank() || target !in targetLanguageTags || source == target) return null
            return TranslationRoute(source, target)
        }
    }

    data class AnyPairWithin(
        val languageTags: Set<String>,
    ) : TranslationLanguageCapability {
        init {
            require(languageTags.size >= 2) { "At least two languages are required" }
            require(languageTags.all { it == normalizeLanguageTag(it) }) {
                "Language tags must be normalized"
            }
        }

        override fun routeFor(
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): TranslationRoute? {
            val source = normalizeLanguageTag(sourceLanguageTag)
            val target = normalizeLanguageTag(targetLanguageTag)
            if (source !in languageTags || target !in languageTags || source == target) return null
            return TranslationRoute(source, target)
        }
    }

    /** The remote model owns the final language matrix; the app validates only a non-empty pair. */
    data object ProviderConfigured : TranslationLanguageCapability {
        override fun routeFor(
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): TranslationRoute? {
            val source = normalizeLanguageTag(sourceLanguageTag)
            val target = normalizeLanguageTag(targetLanguageTag)
            if (source.isBlank() || target.isBlank() || source == target) return null
            return TranslationRoute(source, target)
        }
    }
}

data class TranslationInputCapability(
    val mode: TranslationInputMode,
    val maximumCharacters: Int? = null,
    val contextWindowTokens: Int? = null,
    val reservedOutputTokens: Int? = null,
    val preservesLineBreaks: Boolean = true,
    val requiresNetworkForInference: Boolean = false,
) {
    init {
        require(maximumCharacters == null || maximumCharacters > 0)
        require(contextWindowTokens == null || contextWindowTokens > 0)
        require(reservedOutputTokens == null || reservedOutputTokens > 0)
        require(
            contextWindowTokens == null ||
                reservedOutputTokens == null ||
                reservedOutputTokens < contextWindowTokens,
        ) {
            "Reserved output tokens must fit inside the context window"
        }
    }
}

enum class TranslationModelStorageLocation {
    APP_PRIVATE_NO_BACKUP,
    SDK_MANAGED,
    REMOTE_PROVIDER,
    NOT_PROVISIONED,
}

enum class TranslationModelDistribution {
    PINNED_HASH_VERIFIED_DOWNLOAD,
    SDK_MANAGED_DOWNLOAD,
    USER_CONFIGURED_REMOTE,
    EVALUATION_ONLY,
}

enum class TranslationModelSizeClass {
    NONE,
    SMALL_UNDER_128_MIB,
    MEDIUM_128_TO_512_MIB,
    LARGE_OVER_1_GIB,
}

data class TranslationModelStorageCapability(
    val location: TranslationModelStorageLocation,
    val distribution: TranslationModelDistribution,
    val sizeClass: TranslationModelSizeClass,
    val expectedLocalBytes: LongRange? = null,
    val userRemovableFromApp: Boolean,
    val localModelDescriptor: TranslationLocalModelDescriptor? = null,
) {
    init {
        require(expectedLocalBytes == null || expectedLocalBytes.first >= 0L)
        require(expectedLocalBytes == null || expectedLocalBytes.last >= expectedLocalBytes.first)
        if (sizeClass == TranslationModelSizeClass.NONE) {
            require(expectedLocalBytes == null || expectedLocalBytes.last == 0L)
        }
        localModelDescriptor?.let { descriptor ->
            require(location == TranslationModelStorageLocation.APP_PRIVATE_NO_BACKUP)
            require(expectedLocalBytes == descriptor.expectedBytes..descriptor.expectedBytes)
            require(userRemovableFromApp)
        }
    }
}

data class TranslationLocalModelDescriptor(
    val id: String,
    val revision: String,
    val relativeDirectory: String,
    val fileName: String,
    val expectedBytes: Long,
    val sha256: String,
) {
    init {
        require(id.isNotBlank())
        require(revision.isNotBlank())
        require(relativeDirectory.isNotBlank() && !relativeDirectory.startsWith('/'))
        require(".." !in relativeDirectory.split('/'))
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName)
        require(expectedBytes > 0L)
        require(isSha256(sha256))
    }
}

enum class TranslationPerRequestCancellation {
    /** Individual [TranslationCall] values do not interrupt queued or active work. */
    NO_PER_REQUEST_CANCEL,

    /** [TranslationCall.cancel] propagates to the currently active request on a best-effort basis. */
    ACTIVE_REQUEST_BEST_EFFORT,
}

enum class TranslationCloseBehavior {
    /** Closing interrupts active work and discards queued work. */
    PREEMPT_ACTIVE_AND_DISCARD_QUEUED,

    /** Marks closed without interrupting the executor, drains it, then releases the runtime. */
    MARK_CLOSED_DRAIN_EXECUTOR_THEN_RELEASE_RUNTIME,

    /** The candidate has no application backend whose close behavior can be asserted. */
    NOT_IMPLEMENTED,
}

data class TranslationCancellationCapability(
    val perRequest: TranslationPerRequestCancellation,
    val onClose: TranslationCloseBehavior,
)

enum class TranslationLatencyClass {
    INTERACTIVE,
    VISIBLE_DELAY,
    NETWORK_DEPENDENT,
}

enum class TranslationMemoryClass {
    SMALL,
    MEDIUM,
    VERY_LARGE,
    REMOTE_INFERENCE,
}

data class TranslationPerformanceCapability(
    val latencyClass: TranslationLatencyClass,
    val memoryClass: TranslationMemoryClass,
    val routeObservations: Map<TranslationRoute, TranslationRoutePerformanceObservation> = emptyMap(),
) {
    init {
        require(routeObservations.keys.all { route ->
            route.sourceLanguageTag != route.targetLanguageTag
        })
    }
}

enum class TranslationProcessMeasurementScope {
    APPLICATION_PROCESS,
    STANDALONE_NATIVE_RUNNER,
}

data class TranslationRoutePerformanceObservation(
    val rawMedianLatencyMillis: Double,
    val observedProcessHighWaterMiB: Double,
    val processMeasurementScope: TranslationProcessMeasurementScope,
) {
    init {
        require(rawMedianLatencyMillis.isFinite() && rawMedianLatencyMillis >= 0.0)
        require(observedProcessHighWaterMiB.isFinite() && observedProcessHighWaterMiB >= 0.0)
    }
}

enum class TranslationAttributionMode {
    PACKAGED_NOTICES,
    DYNAMIC_REMOTE_PROVIDER,
    BENCHMARK_DOCUMENTATION,
}

data class TranslationAttributionComponent(
    val name: String,
    val revision: String,
    val license: String,
    val sourceUrl: String,
) {
    init {
        require(name.isNotBlank())
        require(revision.isNotBlank())
        require(license.isNotBlank())
        require(sourceUrl.startsWith("https://"))
    }
}

data class TranslationAttributionCapability(
    val mode: TranslationAttributionMode,
    val components: List<TranslationAttributionComponent>,
) {
    init {
        if (mode != TranslationAttributionMode.DYNAMIC_REMOTE_PROVIDER) {
            require(components.isNotEmpty()) { "Static providers require attribution records" }
        }
    }
}

enum class UpstreamPullRequestState {
    OPEN,
    MERGED,
    CLOSED_UNMERGED,
}

enum class RuntimeSupportVerificationStatus {
    UNVERIFIED,
    VERIFIED_CONTAINS_UPSTREAM_MERGE,
}

enum class RuntimeSupportEvidenceKind {
    CI_MERGE_ANCESTRY,
    VERSIONED_COMPATIBILITY_RECORD,
}

/**
 * Versioned evidence binding one pinned runtime to one merged upstream support commit.
 * Merely supplying two SHA-shaped strings is not evidence that one contains the other.
 */
data class TranslationRuntimeSupportEvidence(
    val kind: RuntimeSupportEvidenceKind,
    val verifiedUpstreamMergeCommit: String,
    val verifiedPinnedRuntimeCommit: String,
    val observedRepositoryGitlinkCommit: String,
    val mergeCommitIsAncestorOfRuntime: Boolean,
    val verifiedRunnableModelSha256: String,
    val evidenceReference: String,
) {
    init {
        require(isFullGitSha(verifiedUpstreamMergeCommit))
        require(isFullGitSha(verifiedPinnedRuntimeCommit))
        require(isFullGitSha(observedRepositoryGitlinkCommit))
        require(isSha256(verifiedRunnableModelSha256))
        require(
            evidenceReference.startsWith("https://") ||
                evidenceReference.startsWith("repo://"),
        ) {
            "Runtime evidence must reference a CI result or versioned repository record"
        }
    }

    fun verifies(
        upstreamMergeCommit: String?,
        pinnedRuntimeCommit: String?,
        runnableModelSha256: String?,
    ): Boolean =
        verifiedUpstreamMergeCommit == upstreamMergeCommit &&
            verifiedPinnedRuntimeCommit == pinnedRuntimeCommit &&
            observedRepositoryGitlinkCommit == pinnedRuntimeCommit &&
            mergeCommitIsAncestorOfRuntime &&
            verifiedRunnableModelSha256 == runnableModelSha256
}

/**
 * A deliberately fail-closed gate for a model format that is not in the pinned runtime.
 * A pull-request head and SHA-shaped values are observations only. Selection requires an
 * explicitly merged PR plus CI or versioned evidence that the exact pinned runtime contains
 * the exact upstream merge commit.
 */
data class TranslationEvaluationGate(
    val upstreamPullRequestUrl: String,
    val upstreamPullRequestState: UpstreamPullRequestState,
    val observedPullRequestHeadCommit: String,
    val upstreamSupportMergeCommit: String?,
    val pinnedRuntimeCommit: String?,
    val runtimeSupportVerificationStatus: RuntimeSupportVerificationStatus,
    val runtimeSupportEvidence: TranslationRuntimeSupportEvidence?,
    val pinnedModelRevision: String,
    val sourceModelSha256: String,
    val runnableModelSha256: String,
    val modelTransformationId: String,
    val modelTransformationManifestSha256: String,
    val evaluationEvidenceId: String,
    val evaluationEvidenceSha256: String,
) {
    val isSatisfied: Boolean
        get() = upstreamPullRequestState == UpstreamPullRequestState.MERGED &&
            isFullGitSha(upstreamSupportMergeCommit) &&
            isFullGitSha(pinnedRuntimeCommit) &&
            runtimeSupportVerificationStatus ==
                RuntimeSupportVerificationStatus.VERIFIED_CONTAINS_UPSTREAM_MERGE &&
            runtimeSupportEvidence?.verifies(
                upstreamSupportMergeCommit,
                pinnedRuntimeCommit,
                runnableModelSha256,
            ) == true &&
            isFullGitSha(pinnedModelRevision) &&
            isSha256(sourceModelSha256) &&
            isSha256(runnableModelSha256) &&
            modelTransformationId.isNotBlank() &&
            isSha256(modelTransformationManifestSha256) &&
            evaluationEvidenceId.isNotBlank() &&
            isSha256(evaluationEvidenceSha256)

    val unmetRequirements: Set<String>
        get() = buildSet {
            if (upstreamPullRequestState != UpstreamPullRequestState.MERGED) {
                add("UPSTREAM_PULL_REQUEST_MERGED")
            }
            if (!isFullGitSha(upstreamSupportMergeCommit)) {
                add("UPSTREAM_MERGE_COMMIT_RECORDED")
            }
            if (!isFullGitSha(pinnedRuntimeCommit)) add("SUPPORTED_RUNTIME_PINNED")
            if (
                runtimeSupportVerificationStatus !=
                RuntimeSupportVerificationStatus.VERIFIED_CONTAINS_UPSTREAM_MERGE ||
                runtimeSupportEvidence?.verifies(
                    upstreamSupportMergeCommit,
                    pinnedRuntimeCommit,
                    runnableModelSha256,
                ) != true
            ) {
                add("PINNED_RUNTIME_CONTAINS_MERGE_VERIFIED")
            }
            if (!isFullGitSha(pinnedModelRevision)) add("MODEL_REVISION_PINNED")
            if (!isSha256(sourceModelSha256)) add("SOURCE_MODEL_SHA256_PINNED")
            if (!isSha256(runnableModelSha256)) add("RUNNABLE_MODEL_SHA256_PINNED")
            if (
                modelTransformationId.isBlank() ||
                !isSha256(modelTransformationManifestSha256)
            ) {
                add("MODEL_TRANSFORMATION_RECORDED")
            }
            if (evaluationEvidenceId.isBlank() || !isSha256(evaluationEvidenceSha256)) {
                add("EVALUATION_EVIDENCE_PINNED")
            }
        }

    init {
        require(upstreamPullRequestUrl.startsWith("https://"))
        require(isFullGitSha(observedPullRequestHeadCommit))
    }
}

enum class MiddleTierAdmissionFailure {
    RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED,
    REQUIRED_ROUTE_MISSING,
    DUPLICATE_ROUTE_MEASUREMENT,
    UNEXPECTED_ROUTE_MEASUREMENT,
    QUALITY_RETENTION_BELOW_THRESHOLD,
    CRITICAL_CHECK_REGRESSION,
    RAW_MEDIAN_LATENCY_ABOVE_THRESHOLD,
    APP_PIPELINE_MEASUREMENT_MISSING,
    APP_PIPELINE_MEDIAN_LATENCY_ABOVE_THRESHOLD,
    APP_PIPELINE_P95_LATENCY_ABOVE_THRESHOLD,
    APP_PIPELINE_TIMEOUTS_ABOVE_THRESHOLD,
    INTEGRATED_RELEASE_MEASUREMENT_MISSING,
    PROCESS_PSS_ABOVE_THRESHOLD,
    HIGH_WATER_MEMORY_ABOVE_THRESHOLD,
    LMK_EVENTS_ABOVE_THRESHOLD,
    HOT_RUN_TOO_SHORT,
    THERMAL_SAMPLING_INSUFFICIENT,
    THERMAL_STATUS_ABOVE_THRESHOLD,
}

data class MiddleTierAppPipelineMeasurement(
    val medianLatencyMillis: Double?,
    val p95LatencyMillis: Double?,
    val timeoutCount: Int?,
) {
    val isComplete: Boolean
        get() = medianLatencyMillis != null && p95LatencyMillis != null && timeoutCount != null

    init {
        require(medianLatencyMillis == null || medianLatencyMillis.isFinite())
        require(medianLatencyMillis == null || medianLatencyMillis >= 0.0)
        require(p95LatencyMillis == null || p95LatencyMillis.isFinite())
        require(p95LatencyMillis == null || p95LatencyMillis >= 0.0)
        require(timeoutCount == null || timeoutCount >= 0)
    }
}

data class MiddleTierRouteMeasurement(
    val route: TranslationRoute,
    val q4BleuRetentionPercent: Double,
    val criticalCheckRegressionsAgainstShippingLite: Int,
    val rawMedianLatencyMillis: Double,
    val appPipeline: MiddleTierAppPipelineMeasurement?,
) {
    init {
        require(q4BleuRetentionPercent.isFinite() && q4BleuRetentionPercent >= 0.0)
        require(criticalCheckRegressionsAgainstShippingLite >= 0)
        require(rawMedianLatencyMillis.isFinite() && rawMedianLatencyMillis >= 0.0)
    }
}

data class MiddleTierIntegratedReleaseMeasurement(
    val processPssBytes: Long,
    val processHighWaterBytes: Long,
    val lmkEventCount: Int,
    val sustainedHotRunMinutes: Int,
    val thermalStatusSamples: List<Int>,
) {
    val maximumThermalStatus: Int
        get() = thermalStatusSamples.maxOrNull() ?: Int.MAX_VALUE

    init {
        require(processPssBytes >= 0L)
        require(processHighWaterBytes >= processPssBytes)
        require(lmkEventCount >= 0)
        require(sustainedHotRunMinutes >= 0)
        require(thermalStatusSamples.all { it >= 0 })
    }
}

data class MiddleTierCandidateMeasurement(
    val routeMeasurements: List<MiddleTierRouteMeasurement>,
    val integratedRelease: MiddleTierIntegratedReleaseMeasurement?,
)

data class MiddleTierAdmissionPolicy(
    val requiredRoutes: Set<TranslationRoute>,
    val minimumQ4BleuRetentionPercent: Double,
    val maximumCriticalCheckRegressionsAgainstShippingLite: Int,
    val maximumRawMedianLatencyMillisExclusive: Double,
    val maximumAppPipelineMedianLatencyMillisExclusive: Double,
    val maximumAppPipelineP95LatencyMillisExclusive: Double,
    val maximumAppPipelineTimeoutCount: Int,
    val maximumIntegratedProcessPssBytesExclusive: Long,
    val maximumIntegratedProcessHighWaterBytesExclusive: Long,
    val maximumLmkEventCount: Int,
    val minimumSustainedHotRunMinutes: Int,
    val minimumThermalStatusSampleCount: Int,
    val maximumThermalStatus: Int,
) {
    init {
        require(requiredRoutes.isNotEmpty())
        require(minimumQ4BleuRetentionPercent.isFinite())
        require(minimumQ4BleuRetentionPercent >= 0.0)
        require(maximumCriticalCheckRegressionsAgainstShippingLite >= 0)
        require(
            maximumRawMedianLatencyMillisExclusive.isFinite() &&
                maximumRawMedianLatencyMillisExclusive > 0.0,
        )
        require(
            maximumAppPipelineMedianLatencyMillisExclusive.isFinite() &&
                maximumAppPipelineMedianLatencyMillisExclusive > 0.0,
        )
        require(
            maximumAppPipelineP95LatencyMillisExclusive.isFinite() &&
                maximumAppPipelineP95LatencyMillisExclusive > 0.0,
        )
        require(maximumAppPipelineTimeoutCount >= 0)
        require(maximumIntegratedProcessPssBytesExclusive > 0L)
        require(maximumIntegratedProcessHighWaterBytesExclusive > 0L)
        require(maximumLmkEventCount >= 0)
        require(minimumSustainedHotRunMinutes > 0)
        require(minimumThermalStatusSampleCount > 2)
        require(maximumThermalStatus >= 0)
    }

    fun evaluate(
        measurement: MiddleTierCandidateMeasurement,
        evaluationGate: TranslationEvaluationGate,
    ): Set<MiddleTierAdmissionFailure> = buildSet {
        if (!evaluationGate.isSatisfied) {
            add(MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED)
        }

        val measurementsByRoute = measurement.routeMeasurements.groupBy { it.route }
        if (requiredRoutes.any { measurementsByRoute[it].isNullOrEmpty() }) {
            add(MiddleTierAdmissionFailure.REQUIRED_ROUTE_MISSING)
        }
        if (measurementsByRoute.values.any { it.size > 1 }) {
            add(MiddleTierAdmissionFailure.DUPLICATE_ROUTE_MEASUREMENT)
        }
        if (measurementsByRoute.keys.any { it !in requiredRoutes }) {
            add(MiddleTierAdmissionFailure.UNEXPECTED_ROUTE_MEASUREMENT)
        }

        requiredRoutes.mapNotNull { route ->
            measurementsByRoute[route]?.singleOrNull()
        }.forEach { routeMeasurement ->
            if (routeMeasurement.q4BleuRetentionPercent < minimumQ4BleuRetentionPercent) {
                add(MiddleTierAdmissionFailure.QUALITY_RETENTION_BELOW_THRESHOLD)
            }
            if (
                routeMeasurement.criticalCheckRegressionsAgainstShippingLite >
                maximumCriticalCheckRegressionsAgainstShippingLite
            ) {
                add(MiddleTierAdmissionFailure.CRITICAL_CHECK_REGRESSION)
            }
            if (routeMeasurement.rawMedianLatencyMillis >= maximumRawMedianLatencyMillisExclusive) {
                add(MiddleTierAdmissionFailure.RAW_MEDIAN_LATENCY_ABOVE_THRESHOLD)
            }
            val pipeline = routeMeasurement.appPipeline
            if (pipeline?.isComplete != true) {
                add(MiddleTierAdmissionFailure.APP_PIPELINE_MEASUREMENT_MISSING)
            } else {
                if (
                    checkNotNull(pipeline.medianLatencyMillis) >=
                    maximumAppPipelineMedianLatencyMillisExclusive
                ) {
                    add(MiddleTierAdmissionFailure.APP_PIPELINE_MEDIAN_LATENCY_ABOVE_THRESHOLD)
                }
                if (
                    checkNotNull(pipeline.p95LatencyMillis) >=
                    maximumAppPipelineP95LatencyMillisExclusive
                ) {
                    add(MiddleTierAdmissionFailure.APP_PIPELINE_P95_LATENCY_ABOVE_THRESHOLD)
                }
                if (checkNotNull(pipeline.timeoutCount) > maximumAppPipelineTimeoutCount) {
                    add(MiddleTierAdmissionFailure.APP_PIPELINE_TIMEOUTS_ABOVE_THRESHOLD)
                }
            }
        }

        val integrated = measurement.integratedRelease
        if (integrated == null) {
            add(MiddleTierAdmissionFailure.INTEGRATED_RELEASE_MEASUREMENT_MISSING)
        } else {
            if (integrated.processPssBytes >= maximumIntegratedProcessPssBytesExclusive) {
                add(MiddleTierAdmissionFailure.PROCESS_PSS_ABOVE_THRESHOLD)
            }
            if (
                integrated.processHighWaterBytes >=
                maximumIntegratedProcessHighWaterBytesExclusive
            ) {
                add(MiddleTierAdmissionFailure.HIGH_WATER_MEMORY_ABOVE_THRESHOLD)
            }
            if (integrated.lmkEventCount > maximumLmkEventCount) {
                add(MiddleTierAdmissionFailure.LMK_EVENTS_ABOVE_THRESHOLD)
            }
            if (integrated.sustainedHotRunMinutes < minimumSustainedHotRunMinutes) {
                add(MiddleTierAdmissionFailure.HOT_RUN_TOO_SHORT)
            }
            if (integrated.thermalStatusSamples.size < minimumThermalStatusSampleCount) {
                add(MiddleTierAdmissionFailure.THERMAL_SAMPLING_INSUFFICIENT)
            }
            if (integrated.maximumThermalStatus > maximumThermalStatus) {
                add(MiddleTierAdmissionFailure.THERMAL_STATUS_ABOVE_THRESHOLD)
            }
        }
    }

    companion object {
        /** Published before any provider can be promoted to the daily middle tier. */
        val DAILY_MIDDLE_TIER = MiddleTierAdmissionPolicy(
            requiredRoutes = setOf(
                TranslationRoute("en", "zh"),
                TranslationRoute("ja", "zh"),
            ),
            minimumQ4BleuRetentionPercent = 95.0,
            maximumCriticalCheckRegressionsAgainstShippingLite = 0,
            maximumRawMedianLatencyMillisExclusive = 350.0,
            maximumAppPipelineMedianLatencyMillisExclusive = 750.0,
            maximumAppPipelineP95LatencyMillisExclusive = 1_500.0,
            maximumAppPipelineTimeoutCount = 0,
            maximumIntegratedProcessPssBytesExclusive = 1_073_741_824L, // 1.0 GiB
            maximumIntegratedProcessHighWaterBytesExclusive = 1_288_490_189L, // 1.2 GiB
            maximumLmkEventCount = 0,
            minimumSustainedHotRunMinutes = 30,
            minimumThermalStatusSampleCount = 30,
            maximumThermalStatus = 1,
        )
    }
}

data class TranslationProviderProfile(
    val id: TranslationProviderId,
    val displayName: String,
    val availability: TranslationProviderAvailability,
    val languages: TranslationLanguageCapability,
    val evaluatedRoutes: Set<TranslationRoute>,
    val input: TranslationInputCapability,
    val modelStorage: TranslationModelStorageCapability,
    val cancellation: TranslationCancellationCapability,
    val performance: TranslationPerformanceCapability,
    val attribution: TranslationAttributionCapability,
    val evaluationGate: TranslationEvaluationGate? = null,
    val middleTierAdmissionPolicy: MiddleTierAdmissionPolicy? = null,
) {
    val isSelectable: Boolean
        get() = (
            availability == TranslationProviderAvailability.SHIPPING ||
                availability == TranslationProviderAvailability.EXPERIMENTAL
            ) && evaluationGate?.isSatisfied != false

    fun supports(sourceLanguageTag: String, targetLanguageTag: String): Boolean =
        languages.routeFor(sourceLanguageTag, targetLanguageTag) != null

    init {
        require(displayName.isNotBlank())
        require(evaluatedRoutes.all {
            languages.routeFor(it.sourceLanguageTag, it.targetLanguageTag) != null
        }) {
            "Evaluated routes must be supported by the provider"
        }
        if (availability == TranslationProviderAvailability.EVALUATION_BLOCKED) {
            require(evaluationGate != null && !evaluationGate.isSatisfied) {
                "A blocked provider requires an unsatisfied evaluation gate"
            }
            require(middleTierAdmissionPolicy != null) {
                "A middle-tier candidate requires a published admission policy"
            }
        }
    }
}

object BergamotLiteProviderContract {
    val enZh = TranslationRoute("en", "zh")
    val jaZhViaEn = TranslationRoute("ja", "zh", listOf("en"))

    val modelIdsByRoute: Map<TranslationRoute, List<String>> = linkedMapOf(
        enZh to listOf("en-zh"),
        jaZhViaEn to listOf("ja-en", "en-zh"),
    )
}

object HyMt2Q4ProviderContract {
    const val CONTEXT_WINDOW_TOKENS = 2_048
    const val RESERVED_OUTPUT_TOKENS = 256
    const val MODEL_REPOSITORY = "tencent/Hy-MT2-1.8B-GGUF"
    const val MODEL_REVISION = "1cd5208700acedef4ef93019b6cfc148b8522d45"
    const val MODEL_RELATIVE_DIRECTORY = "models/hymt2-q4"
    const val MODEL_FILE_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
    const val MODEL_SIZE_BYTES = 1_133_080_448L
    const val MODEL_SHA256 =
        "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"

    val modelDescriptor = TranslationLocalModelDescriptor(
        id = "hymt2-q4",
        revision = MODEL_REVISION,
        relativeDirectory = MODEL_RELATIVE_DIRECTORY,
        fileName = MODEL_FILE_NAME,
        expectedBytes = MODEL_SIZE_BYTES,
        sha256 = MODEL_SHA256,
    )
}

object OnlineByokProviderContract {
    const val MAX_INPUT_CHARACTERS = 6_000
}

object TranslationProviderProfiles {
    private val enZh = BergamotLiteProviderContract.enZh
    private val jaZhViaEn = BergamotLiteProviderContract.jaZhViaEn
    private val jaZh = TranslationRoute("ja", "zh")

    val bergamotLite = TranslationProviderProfile(
        id = TranslationProviderId.BERGAMOT_LITE,
        displayName = "Bergamot Lite",
        availability = TranslationProviderAvailability.SHIPPING,
        languages = TranslationLanguageCapability.ExplicitRoutes(
            BergamotLiteProviderContract.modelIdsByRoute.keys,
        ),
        evaluatedRoutes = BergamotLiteProviderContract.modelIdsByRoute.keys,
        input = TranslationInputCapability(mode = TranslationInputMode.CLAUSE_PLAN),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.APP_PRIVATE_NO_BACKUP,
            distribution = TranslationModelDistribution.PINNED_HASH_VERIFIED_DOWNLOAD,
            sizeClass = TranslationModelSizeClass.SMALL_UNDER_128_MIB,
            userRemovableFromApp = true,
        ),
        cancellation = TranslationCancellationCapability(
            perRequest = TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            onClose = TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
        ),
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.INTERACTIVE,
            memoryClass = TranslationMemoryClass.MEDIUM,
            routeObservations = mapOf(
                enZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 35.547,
                    observedProcessHighWaterMiB = 456.58984375,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
                jaZhViaEn to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 64.410,
                    observedProcessHighWaterMiB = 767.625,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
            ),
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.PACKAGED_NOTICES,
            components = listOf(
                TranslationAttributionComponent(
                    name = "Bergamot Translator Android runtime",
                    revision = "9271618ebbdc5d21ac4dc4df9e72beb7ce644774",
                    license = "Mozilla Public License 2.0",
                    sourceUrl = "https://github.com/browsermt/bergamot-translator",
                ),
                TranslationAttributionComponent(
                    name = "Firefox Translations models",
                    revision = "e7957fc407441a5e3e35bbcbf9d60d9b35764618",
                    license = "Mozilla Public License 2.0",
                    sourceUrl = "https://github.com/mozilla/firefox-translations-models",
                ),
            ),
        ),
    )

    val hyMt2Q4Full = TranslationProviderProfile(
        id = TranslationProviderId.HY_MT2_Q4_FULL,
        displayName = "HY-MT2 Q4 Full Experimental",
        availability = TranslationProviderAvailability.EXPERIMENTAL,
        languages = TranslationLanguageCapability.AnySourceToTargets(setOf("zh")),
        evaluatedRoutes = setOf(enZh, jaZh),
        input = TranslationInputCapability(
            mode = TranslationInputMode.CLAUSE_PLAN,
            contextWindowTokens = HyMt2Q4ProviderContract.CONTEXT_WINDOW_TOKENS,
            reservedOutputTokens = HyMt2Q4ProviderContract.RESERVED_OUTPUT_TOKENS,
        ),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.APP_PRIVATE_NO_BACKUP,
            distribution = TranslationModelDistribution.PINNED_HASH_VERIFIED_DOWNLOAD,
            sizeClass = TranslationModelSizeClass.LARGE_OVER_1_GIB,
            expectedLocalBytes =
                HyMt2Q4ProviderContract.MODEL_SIZE_BYTES..HyMt2Q4ProviderContract.MODEL_SIZE_BYTES,
            userRemovableFromApp = true,
            localModelDescriptor = HyMt2Q4ProviderContract.modelDescriptor,
        ),
        cancellation = TranslationCancellationCapability(
            perRequest = TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            onClose =
                TranslationCloseBehavior.MARK_CLOSED_DRAIN_EXECUTOR_THEN_RELEASE_RUNTIME,
        ),
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.VISIBLE_DELAY,
            memoryClass = TranslationMemoryClass.VERY_LARGE,
            routeObservations = mapOf(
                enZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 753.498,
                    observedProcessHighWaterMiB = 2_200.54296875,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
                jaZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 653.432,
                    observedProcessHighWaterMiB = 2_200.54296875,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
            ),
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.PACKAGED_NOTICES,
            components = listOf(
                TranslationAttributionComponent(
                    name = "HY-MT2 1.8B Q4_K_M",
                    revision = HyMt2Q4ProviderContract.MODEL_REVISION,
                    license = "Apache License 2.0",
                    sourceUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF",
                ),
                TranslationAttributionComponent(
                    name = "llama.cpp Android runtime",
                    revision = "caa596ab3f0f8768ee326d6e3d5d39782194676c",
                    license = "MIT License",
                    sourceUrl = "https://github.com/ggml-org/llama.cpp",
                ),
            ),
        ),
    )

    val onlineByok = TranslationProviderProfile(
        id = TranslationProviderId.ONLINE_BYOK,
        displayName = "Online BYOK",
        availability = TranslationProviderAvailability.SHIPPING,
        languages = TranslationLanguageCapability.ProviderConfigured,
        evaluatedRoutes = emptySet(),
        input = TranslationInputCapability(
            mode = TranslationInputMode.WHOLE_REGION,
            maximumCharacters = OnlineByokProviderContract.MAX_INPUT_CHARACTERS,
            requiresNetworkForInference = true,
        ),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.REMOTE_PROVIDER,
            distribution = TranslationModelDistribution.USER_CONFIGURED_REMOTE,
            sizeClass = TranslationModelSizeClass.NONE,
            expectedLocalBytes = 0L..0L,
            userRemovableFromApp = false,
        ),
        cancellation = TranslationCancellationCapability(
            perRequest = TranslationPerRequestCancellation.ACTIVE_REQUEST_BEST_EFFORT,
            onClose = TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
        ),
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.NETWORK_DEPENDENT,
            memoryClass = TranslationMemoryClass.REMOTE_INFERENCE,
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.DYNAMIC_REMOTE_PROVIDER,
            components = emptyList(),
        ),
    )

    val mlKitBenchmark = TranslationProviderProfile(
        id = TranslationProviderId.ML_KIT_BENCHMARK,
        displayName = "ML Kit Translate benchmark",
        availability = TranslationProviderAvailability.BENCHMARK_ONLY,
        languages = TranslationLanguageCapability.AnyPairWithin(
            setOf("zh", "en", "ja", "ko", "fr", "de", "es", "ru"),
        ),
        evaluatedRoutes = setOf(enZh, jaZh),
        input = TranslationInputCapability(mode = TranslationInputMode.CLAUSE_PLAN),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.SDK_MANAGED,
            distribution = TranslationModelDistribution.SDK_MANAGED_DOWNLOAD,
            sizeClass = TranslationModelSizeClass.SMALL_UNDER_128_MIB,
            userRemovableFromApp = false,
        ),
        cancellation = TranslationCancellationCapability(
            perRequest = TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            onClose = TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
        ),
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.INTERACTIVE,
            memoryClass = TranslationMemoryClass.SMALL,
            routeObservations = mapOf(
                enZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 27.643,
                    observedProcessHighWaterMiB = 271.62890625,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.APPLICATION_PROCESS,
                ),
                jaZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 39.627,
                    observedProcessHighWaterMiB = 324.109375,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.APPLICATION_PROCESS,
                ),
            ),
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.BENCHMARK_DOCUMENTATION,
            components = listOf(
                TranslationAttributionComponent(
                    name = "ML Kit Translate",
                    revision = "17.0.3",
                    license = "ML Kit Terms of Service",
                    sourceUrl = "https://developers.google.com/ml-kit/terms",
                ),
            ),
        ),
    )

    val hyMt2StqCandidate = TranslationProviderProfile(
        id = TranslationProviderId.HY_MT2_STQ_CANDIDATE,
        displayName = "HY-MT2 STQ1_0 1.25-bit candidate",
        availability = TranslationProviderAvailability.EVALUATION_BLOCKED,
        languages = TranslationLanguageCapability.AnySourceToTargets(setOf("zh")),
        evaluatedRoutes = setOf(enZh, jaZh),
        input = TranslationInputCapability(
            mode = TranslationInputMode.CLAUSE_PLAN,
            contextWindowTokens = 2_048,
            reservedOutputTokens = 256,
        ),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.NOT_PROVISIONED,
            distribution = TranslationModelDistribution.EVALUATION_ONLY,
            sizeClass = TranslationModelSizeClass.MEDIUM_128_TO_512_MIB,
            expectedLocalBytes = 461_860_800L..461_860_800L,
            userRemovableFromApp = false,
        ),
        cancellation = TranslationCancellationCapability(
            perRequest = TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            onClose = TranslationCloseBehavior.NOT_IMPLEMENTED,
        ),
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.VISIBLE_DELAY,
            memoryClass = TranslationMemoryClass.MEDIUM,
            routeObservations = mapOf(
                enZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 615.727,
                    observedProcessHighWaterMiB = 903.3203125,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
                jaZh to TranslationRoutePerformanceObservation(
                    rawMedianLatencyMillis = 622.125,
                    observedProcessHighWaterMiB = 903.3203125,
                    processMeasurementScope =
                        TranslationProcessMeasurementScope.STANDALONE_NATIVE_RUNNER,
                ),
            ),
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.BENCHMARK_DOCUMENTATION,
            components = listOf(
                TranslationAttributionComponent(
                    name = "HY-MT2 1.8B STQ1_0 1.25-bit GGUF",
                    revision = "9df5c824a00a744fb0512a29c640466f4d97dfb0",
                    license = "Apache License 2.0",
                    sourceUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF",
                ),
            ),
        ),
        evaluationGate = TranslationEvaluationGate(
            upstreamPullRequestUrl = "https://github.com/ggml-org/llama.cpp/pull/22836",
            upstreamPullRequestState = UpstreamPullRequestState.OPEN,
            observedPullRequestHeadCommit = "7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7",
            upstreamSupportMergeCommit = null,
            pinnedRuntimeCommit = "caa596ab3f0f8768ee326d6e3d5d39782194676c",
            runtimeSupportVerificationStatus = RuntimeSupportVerificationStatus.UNVERIFIED,
            runtimeSupportEvidence = null,
            pinnedModelRevision = "9df5c824a00a744fb0512a29c640466f4d97dfb0",
            sourceModelSha256 =
                "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
            runnableModelSha256 =
                "e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7",
            modelTransformationId = "retag-legacy-stq-gguf-v1",
            modelTransformationManifestSha256 =
                "b4713fdb0e95c74446688597d7c0bab6cc217379379115dc535c2e89f599f284",
            evaluationEvidenceId = "hymt2-stq-2026-07-30-xiaomi15pro-android16",
            evaluationEvidenceSha256 =
                "3ebf5628c5e21685fae2583a1500129d2f60e3da80a0e0490beaa4e5a57490cd",
        ),
        middleTierAdmissionPolicy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER,
    )

    val editionProfiles: List<TranslationProviderProfile> = listOf(
        bergamotLite,
        hyMt2Q4Full,
        onlineByok,
    )

    val all: List<TranslationProviderProfile> = editionProfiles + listOf(
        mlKitBenchmark,
        hyMt2StqCandidate,
    )

    fun requireById(id: TranslationProviderId): TranslationProviderProfile =
        all.single { it.id == id }
}

private fun normalizeLanguageTag(value: String): String =
    value.trim().lowercase(Locale.ROOT)

private fun isFullGitSha(value: String?): Boolean =
    value?.matches(Regex("[0-9a-f]{40}")) == true

private fun isSha256(value: String?): Boolean =
    value?.matches(Regex("[0-9a-f]{64}")) == true
