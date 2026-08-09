package com.screentranslation.app.ml

import com.screentranslation.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProviderProfileTest {
    @Test
    fun productionEditionsDeclareCompleteDistinctProfiles() {
        val profiles = TranslationProviderProfiles.editionProfiles

        assertEquals(3, profiles.size)
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        profiles.forEach { profile ->
            assertTrue(profile.isSelectable)
            assertTrue(profile.displayName.isNotBlank())
            assertNotNull(profile.languages)
            assertNotNull(profile.input.mode)
            assertNotNull(profile.modelStorage.location)
            assertNotNull(profile.cancellation)
            assertNotNull(profile.performance.latencyClass)
            assertNotNull(profile.performance.memoryClass)
            assertNotNull(profile.attribution.mode)
        }
    }

    @Test
    fun liteRoutesAreExplicitAndJapaneseUsesEnglishPivot() {
        val capability = TranslationProviderProfiles.bergamotLite.languages

        assertEquals(
            TranslationRoute("en", "zh"),
            capability.routeFor(" EN ", "ZH"),
        )
        assertEquals(
            TranslationRoute("ja", "zh", listOf("en")),
            capability.routeFor("ja", "zh"),
        )
        assertNull(capability.routeFor("ko", "zh"))
        assertNull(capability.routeFor("zh", "zh"))
    }

    @Test
    fun fullProfileTargetsChineseAndRetainsQ4ResourceContract() {
        val profile = TranslationProviderProfiles.hyMt2Q4Full

        assertTrue(profile.supports("de", "zh"))
        assertFalse(profile.supports("zh", "zh"))
        assertFalse(profile.supports("en", "ja"))
        assertEquals(TranslationInputMode.CLAUSE_PLAN, profile.input.mode)
        assertEquals(2_048, profile.input.contextWindowTokens)
        assertEquals(256, profile.input.reservedOutputTokens)
        assertEquals(
            1_133_080_448L..1_133_080_448L,
            profile.modelStorage.expectedLocalBytes,
        )
        assertEquals(
            TranslationProviderAvailability.EXPERIMENTAL,
            profile.availability,
        )
    }

    @Test
    fun onlineProfileOwnsWholeRegionLimitAndActiveCancellationPolicy() {
        val profile = TranslationProviderProfiles.onlineByok

        assertTrue(profile.supports("ja", "fr"))
        assertFalse(profile.supports("en", "en"))
        assertEquals(TranslationInputMode.WHOLE_REGION, profile.input.mode)
        assertEquals(6_000, profile.input.maximumCharacters)
        assertTrue(profile.input.requiresNetworkForInference)
        assertEquals(
            TranslationCancellationCapability.ACTIVE_REQUEST_BEST_EFFORT,
            profile.cancellation,
        )
        assertEquals(
            TranslationModelStorageLocation.REMOTE_PROVIDER,
            profile.modelStorage.location,
        )
        assertEquals(
            TranslationAttributionMode.DYNAMIC_REMOTE_PROVIDER,
            profile.attribution.mode,
        )
    }

    @Test
    fun factoryProfileMatchesExactlyOneEditionBuildFlag() {
        val expected = when {
            BuildConfig.BERGAMOT_LITE -> TranslationProviderId.BERGAMOT_LITE
            BuildConfig.HYMT2_Q4_EXPERIMENTAL -> TranslationProviderId.HY_MT2_Q4_FULL
            BuildConfig.ONLINE_LLM -> TranslationProviderId.ONLINE_BYOK
            else -> error("Test variant has no provider flag")
        }

        assertEquals(expected, TranslationBackendFactory.profile.id)
    }

    @Test
    fun stqCandidateIsFailClosedOutsideTheEditionFactory() {
        val profile = TranslationProviderProfiles.hyMt2StqCandidate
        val gate = checkNotNull(profile.evaluationGate)

        assertEquals(
            TranslationProviderAvailability.EVALUATION_BLOCKED,
            profile.availability,
        )
        assertFalse(profile.isSelectable)
        assertFalse(gate.isSatisfied)
        assertEquals(
            setOf("UPSTREAM_SUPPORT_MERGED", "SUPPORTED_RUNTIME_PINNED"),
            gate.unmetRequirements,
        )
        assertFalse(TranslationProviderProfiles.editionProfiles.contains(profile))
        assertEquals(
            MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER,
            profile.middleTierAdmissionPolicy,
        )
    }

    @Test
    fun currentStqEvidenceDoesNotMeetPublishedMiddleTierPolicy() {
        val profile = TranslationProviderProfiles.hyMt2StqCandidate
        val failures = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER.evaluate(
            measurement = MiddleTierCandidateMeasurement(
                minimumQ4BleuRetentionPercentAcrossRequiredRoutes = 88.37,
                criticalCheckRegressionsAgainstShippingLite = 8,
                worstRawMedianLatencyMillis = 622.125,
                processHighWaterBytes = 925_000L * 1_024L,
                thermalRunMinutes = 14,
                maximumThermalStatus = 0,
            ),
            evaluationGate = checkNotNull(profile.evaluationGate),
        )

        assertEquals(
            setOf(
                MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED,
                MiddleTierAdmissionFailure.QUALITY_RETENTION_BELOW_THRESHOLD,
                MiddleTierAdmissionFailure.CRITICAL_CHECK_REGRESSION,
                MiddleTierAdmissionFailure.MEDIAN_LATENCY_ABOVE_THRESHOLD,
                MiddleTierAdmissionFailure.THERMAL_RUN_TOO_SHORT,
            ),
            failures,
        )
        assertFalse(
            failures.contains(MiddleTierAdmissionFailure.HIGH_WATER_MEMORY_ABOVE_THRESHOLD),
        )
        assertFalse(
            failures.contains(MiddleTierAdmissionFailure.THERMAL_STATUS_ABOVE_THRESHOLD),
        )
    }

    @Test
    fun candidateAtEveryPublishedBoundaryIsAdmittedAfterMergeAndPin() {
        val openGate = checkNotNull(TranslationProviderProfiles.hyMt2StqCandidate.evaluationGate)
        val satisfiedGate = openGate.copy(
            upstreamSupportMergeCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            pinnedRuntimeCommit = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val failures = policy.evaluate(
            measurement = MiddleTierCandidateMeasurement(
                minimumQ4BleuRetentionPercentAcrossRequiredRoutes = 95.0,
                criticalCheckRegressionsAgainstShippingLite = 0,
                worstRawMedianLatencyMillis = 349.999,
                processHighWaterBytes = policy.maximumProcessHighWaterBytesExclusive - 1L,
                thermalRunMinutes = 30,
                maximumThermalStatus = 1,
            ),
            evaluationGate = satisfiedGate,
        )

        assertTrue(satisfiedGate.isSatisfied)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun staticProfilesHaveAuditableAttributionComponents() {
        TranslationProviderProfiles.all
            .filter { it.attribution.mode != TranslationAttributionMode.DYNAMIC_REMOTE_PROVIDER }
            .forEach { profile ->
                assertTrue(profile.attribution.components.isNotEmpty())
                profile.attribution.components.forEach { component ->
                    assertTrue(component.revision.isNotBlank())
                    assertTrue(component.license.isNotBlank())
                    assertTrue(component.sourceUrl.startsWith("https://"))
                }
            }
    }
}
