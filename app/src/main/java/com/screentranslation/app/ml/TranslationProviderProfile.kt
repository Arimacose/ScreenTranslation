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
) {
    init {
        require(expectedLocalBytes == null || expectedLocalBytes.first >= 0L)
        require(expectedLocalBytes == null || expectedLocalBytes.last >= expectedLocalBytes.first)
        if (sizeClass == TranslationModelSizeClass.NONE) {
            require(expectedLocalBytes == null || expectedLocalBytes.last == 0L)
        }
    }
}

enum class TranslationCancellationCapability {
    /** Individual [TranslationCall] instances are inert; shutdown is tied to the engine lifetime. */
    ENGINE_LIFETIME_ONLY,

    /** [TranslationCall.cancel] propagates to the currently active request on a best-effort basis. */
    ACTIVE_REQUEST_BEST_EFFORT,
}

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
    val observedMedianLatencyMillis: IntRange? = null,
    val observedProcessHighWaterMiB: IntRange? = null,
) {
    init {
        require(observedMedianLatencyMillis == null || observedMedianLatencyMillis.first >= 0)
        require(observedProcessHighWaterMiB == null || observedProcessHighWaterMiB.first >= 0)
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

/**
 * A deliberately fail-closed gate for a model format that is not in the pinned runtime.
 * A pull-request head is evidence only; it never satisfies the merge or pin requirements.
 */
data class TranslationEvaluationGate(
    val upstreamPullRequestUrl: String,
    val observedPullRequestHeadCommit: String,
    val upstreamSupportMergeCommit: String?,
    val pinnedRuntimeCommit: String?,
    val pinnedModelRevision: String,
    val pinnedModelSha256: String,
) {
    val isSatisfied: Boolean
        get() = isFullGitSha(upstreamSupportMergeCommit) &&
            isFullGitSha(pinnedRuntimeCommit) &&
            isFullGitSha(pinnedModelRevision) &&
            isSha256(pinnedModelSha256)

    val unmetRequirements: Set<String>
        get() = buildSet {
            if (!isFullGitSha(upstreamSupportMergeCommit)) add("UPSTREAM_SUPPORT_MERGED")
            if (!isFullGitSha(pinnedRuntimeCommit)) add("SUPPORTED_RUNTIME_PINNED")
            if (!isFullGitSha(pinnedModelRevision)) add("MODEL_REVISION_PINNED")
            if (!isSha256(pinnedModelSha256)) add("MODEL_SHA256_PINNED")
        }

    init {
        require(upstreamPullRequestUrl.startsWith("https://"))
        require(isFullGitSha(observedPullRequestHeadCommit))
    }
}

enum class MiddleTierAdmissionFailure {
    RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED,
    QUALITY_RETENTION_BELOW_THRESHOLD,
    CRITICAL_CHECK_REGRESSION,
    MEDIAN_LATENCY_ABOVE_THRESHOLD,
    HIGH_WATER_MEMORY_ABOVE_THRESHOLD,
    THERMAL_RUN_TOO_SHORT,
    THERMAL_STATUS_ABOVE_THRESHOLD,
}

data class MiddleTierCandidateMeasurement(
    val minimumQ4BleuRetentionPercentAcrossRequiredRoutes: Double,
    val criticalCheckRegressionsAgainstShippingLite: Int,
    val worstRawMedianLatencyMillis: Double,
    val processHighWaterBytes: Long,
    val thermalRunMinutes: Int,
    val maximumThermalStatus: Int,
) {
    init {
        require(minimumQ4BleuRetentionPercentAcrossRequiredRoutes.isFinite())
        require(minimumQ4BleuRetentionPercentAcrossRequiredRoutes >= 0.0)
        require(criticalCheckRegressionsAgainstShippingLite >= 0)
        require(worstRawMedianLatencyMillis.isFinite() && worstRawMedianLatencyMillis >= 0.0)
        require(processHighWaterBytes >= 0L)
        require(thermalRunMinutes >= 0)
        require(maximumThermalStatus >= 0)
    }
}

data class MiddleTierAdmissionPolicy(
    val minimumQ4BleuRetentionPercent: Double,
    val maximumCriticalCheckRegressionsAgainstShippingLite: Int,
    val maximumRawMedianLatencyMillisExclusive: Double,
    val maximumProcessHighWaterBytesExclusive: Long,
    val minimumThermalRunMinutes: Int,
    val maximumThermalStatus: Int,
) {
    fun evaluate(
        measurement: MiddleTierCandidateMeasurement,
        evaluationGate: TranslationEvaluationGate,
    ): Set<MiddleTierAdmissionFailure> = buildSet {
        if (!evaluationGate.isSatisfied) {
            add(MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED)
        }
        if (
            measurement.minimumQ4BleuRetentionPercentAcrossRequiredRoutes <
            minimumQ4BleuRetentionPercent
        ) {
            add(MiddleTierAdmissionFailure.QUALITY_RETENTION_BELOW_THRESHOLD)
        }
        if (
            measurement.criticalCheckRegressionsAgainstShippingLite >
            maximumCriticalCheckRegressionsAgainstShippingLite
        ) {
            add(MiddleTierAdmissionFailure.CRITICAL_CHECK_REGRESSION)
        }
        if (measurement.worstRawMedianLatencyMillis >= maximumRawMedianLatencyMillisExclusive) {
            add(MiddleTierAdmissionFailure.MEDIAN_LATENCY_ABOVE_THRESHOLD)
        }
        if (measurement.processHighWaterBytes >= maximumProcessHighWaterBytesExclusive) {
            add(MiddleTierAdmissionFailure.HIGH_WATER_MEMORY_ABOVE_THRESHOLD)
        }
        if (measurement.thermalRunMinutes < minimumThermalRunMinutes) {
            add(MiddleTierAdmissionFailure.THERMAL_RUN_TOO_SHORT)
        }
        if (measurement.maximumThermalStatus > maximumThermalStatus) {
            add(MiddleTierAdmissionFailure.THERMAL_STATUS_ABOVE_THRESHOLD)
        }
    }

    companion object {
        /** Published before any provider can be promoted to the daily middle tier. */
        val DAILY_MIDDLE_TIER = MiddleTierAdmissionPolicy(
            minimumQ4BleuRetentionPercent = 95.0,
            maximumCriticalCheckRegressionsAgainstShippingLite = 0,
            maximumRawMedianLatencyMillisExclusive = 350.0,
            maximumProcessHighWaterBytesExclusive = 1_288_490_189L, // 1.2 GiB
            minimumThermalRunMinutes = 30,
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
        get() = availability == TranslationProviderAvailability.SHIPPING ||
            availability == TranslationProviderAvailability.EXPERIMENTAL

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

object TranslationProviderProfiles {
    private val enZh = TranslationRoute("en", "zh")
    private val jaZhViaEn = TranslationRoute("ja", "zh", listOf("en"))
    private val jaZh = TranslationRoute("ja", "zh")

    val bergamotLite = TranslationProviderProfile(
        id = TranslationProviderId.BERGAMOT_LITE,
        displayName = "Bergamot Lite",
        availability = TranslationProviderAvailability.SHIPPING,
        languages = TranslationLanguageCapability.ExplicitRoutes(setOf(enZh, jaZhViaEn)),
        evaluatedRoutes = setOf(enZh, jaZhViaEn),
        input = TranslationInputCapability(mode = TranslationInputMode.CLAUSE_PLAN),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.APP_PRIVATE_NO_BACKUP,
            distribution = TranslationModelDistribution.PINNED_HASH_VERIFIED_DOWNLOAD,
            sizeClass = TranslationModelSizeClass.SMALL_UNDER_128_MIB,
            userRemovableFromApp = true,
        ),
        cancellation = TranslationCancellationCapability.ENGINE_LIFETIME_ONLY,
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.INTERACTIVE,
            memoryClass = TranslationMemoryClass.MEDIUM,
            observedMedianLatencyMillis = 35..64,
            observedProcessHighWaterMiB = 457..768,
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
            contextWindowTokens = 2_048,
            reservedOutputTokens = 256,
        ),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.APP_PRIVATE_NO_BACKUP,
            distribution = TranslationModelDistribution.PINNED_HASH_VERIFIED_DOWNLOAD,
            sizeClass = TranslationModelSizeClass.LARGE_OVER_1_GIB,
            expectedLocalBytes = 1_133_080_448L..1_133_080_448L,
            userRemovableFromApp = true,
        ),
        cancellation = TranslationCancellationCapability.ENGINE_LIFETIME_ONLY,
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.VISIBLE_DELAY,
            memoryClass = TranslationMemoryClass.VERY_LARGE,
            observedMedianLatencyMillis = 653..753,
            observedProcessHighWaterMiB = 2_201..2_201,
        ),
        attribution = TranslationAttributionCapability(
            mode = TranslationAttributionMode.PACKAGED_NOTICES,
            components = listOf(
                TranslationAttributionComponent(
                    name = "HY-MT2 1.8B Q4_K_M",
                    revision = "1cd5208700acedef4ef93019b6cfc148b8522d45",
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
            maximumCharacters = 6_000,
            requiresNetworkForInference = true,
        ),
        modelStorage = TranslationModelStorageCapability(
            location = TranslationModelStorageLocation.REMOTE_PROVIDER,
            distribution = TranslationModelDistribution.USER_CONFIGURED_REMOTE,
            sizeClass = TranslationModelSizeClass.NONE,
            expectedLocalBytes = 0L..0L,
            userRemovableFromApp = false,
        ),
        cancellation = TranslationCancellationCapability.ACTIVE_REQUEST_BEST_EFFORT,
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
        cancellation = TranslationCancellationCapability.ENGINE_LIFETIME_ONLY,
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.INTERACTIVE,
            memoryClass = TranslationMemoryClass.SMALL,
            observedMedianLatencyMillis = 28..40,
            observedProcessHighWaterMiB = 272..324,
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
        cancellation = TranslationCancellationCapability.ENGINE_LIFETIME_ONLY,
        performance = TranslationPerformanceCapability(
            latencyClass = TranslationLatencyClass.VISIBLE_DELAY,
            memoryClass = TranslationMemoryClass.MEDIUM,
            observedMedianLatencyMillis = 616..622,
            observedProcessHighWaterMiB = 904..904,
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
            observedPullRequestHeadCommit = "7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7",
            upstreamSupportMergeCommit = null,
            pinnedRuntimeCommit = null,
            pinnedModelRevision = "9df5c824a00a744fb0512a29c640466f4d97dfb0",
            pinnedModelSha256 =
                "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
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
