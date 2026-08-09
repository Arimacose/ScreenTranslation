package com.screentranslation.app.ml

import com.screentranslation.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslationProviderProfileTest {
    @Test
    fun productionEditionsDeclareCompleteDistinctProfiles() {
        val profiles = TranslationProviderProfiles.editionProfiles

        assertEquals(3, profiles.size)
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        profiles.forEach { profile ->
            assertTrue(profile.isSelectable)
            assertNull(profile.admission)
            assertTrue(profile.displayName.isNotBlank())
            assertNotNull(profile.languages)
            assertNotNull(profile.input.mode)
            assertNotNull(profile.modelStorage.location)
            assertNotNull(profile.cancellation.perRequest)
            assertNotNull(profile.cancellation.onClose)
            assertNotNull(profile.performance.latencyClass)
            assertNotNull(profile.performance.memoryClass)
            assertNotNull(profile.attribution.mode)
        }
    }

    @Test
    fun liteRoutesAreExplicitAndJapaneseUsesEnglishPivot() {
        val capability = TranslationProviderProfiles.bergamotLite.languages

        assertEquals(TranslationRoute("en", "zh"), capability.routeFor(" EN ", "ZH"))
        assertEquals(
            TranslationRoute("ja", "zh", listOf("en")),
            capability.routeFor("ja", "zh"),
        )
        assertNull(capability.routeFor("ko", "zh"))
        assertNull(capability.routeFor("zh", "zh"))
        assertEquals(
            BergamotLiteProviderContract.modelIdsByRoute.keys,
            TranslationProviderProfiles.bergamotLite.evaluatedRoutes,
        )
    }

    @Test
    fun fullProfileTargetsChineseAndRetainsQ4ResourceContract() {
        val profile = TranslationProviderProfiles.hyMt2Q4Full
        val descriptor = checkNotNull(profile.modelStorage.localModelDescriptor)

        assertTrue(profile.supports("de", "zh"))
        assertFalse(profile.supports("zh", "zh"))
        assertFalse(profile.supports("en", "ja"))
        assertEquals(TranslationInputMode.CLAUSE_PLAN, profile.input.mode)
        assertEquals(HyMt2Q4ProviderContract.CONTEXT_WINDOW_TOKENS, profile.input.contextWindowTokens)
        assertEquals(HyMt2Q4ProviderContract.RESERVED_OUTPUT_TOKENS, profile.input.reservedOutputTokens)
        assertEquals(HyMt2Q4ProviderContract.modelDescriptor, descriptor)
        assertEquals(
            HyMt2Q4ProviderContract.MODEL_SIZE_BYTES..HyMt2Q4ProviderContract.MODEL_SIZE_BYTES,
            profile.modelStorage.expectedLocalBytes,
        )
        assertTrue(profile.modelStorage.userRemovableFromApp)
        assertEquals(TranslationProviderAvailability.EXPERIMENTAL, profile.availability)
        assertEquals(
            TranslationCloseBehavior.MARK_CLOSED_DRAIN_EXECUTOR_THEN_RELEASE_RUNTIME,
            profile.cancellation.onClose,
        )
    }

    @Test
    fun onlineProfileOwnsWholeRegionLimitAndActiveCancellationPolicy() {
        val profile = TranslationProviderProfiles.onlineByok

        assertTrue(profile.supports("ja", "fr"))
        assertFalse(profile.supports("en", "en"))
        assertEquals(TranslationInputMode.WHOLE_REGION, profile.input.mode)
        assertEquals(OnlineByokProviderContract.MAX_INPUT_CHARACTERS, profile.input.maximumCharacters)
        assertTrue(profile.input.requiresNetworkForInference)
        assertEquals(
            TranslationPerRequestCancellation.ACTIVE_REQUEST_BEST_EFFORT,
            profile.cancellation.perRequest,
        )
        assertEquals(
            TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
            profile.cancellation.onClose,
        )
        assertEquals(TranslationModelStorageLocation.REMOTE_PROVIDER, profile.modelStorage.location)
        assertEquals(TranslationAttributionMode.DYNAMIC_REMOTE_PROVIDER, profile.attribution.mode)
    }

    @Test
    fun factoryProfileIsTheSelectedEditionSingleton() {
        val expected = when {
            BuildConfig.BERGAMOT_LITE -> TranslationProviderProfiles.bergamotLite
            BuildConfig.HYMT2_Q4_EXPERIMENTAL -> TranslationProviderProfiles.hyMt2Q4Full
            BuildConfig.ONLINE_LLM -> TranslationProviderProfiles.onlineByok
            else -> error("Test variant has no provider flag")
        }

        assertSame(expected, TranslationBackendFactory.profile)
        requireSelectedProfileSingleton(expected, TranslationBackendFactory.profile)
    }

    @Test(expected = IllegalStateException::class)
    fun idMatchingProfileCopyIsRejectedByFactorySingletonContract() {
        val selected = TranslationBackendFactory.profile

        requireSelectedProfileSingleton(selected, selected.copy())
    }

    @Test
    fun canonicalStqAdmissionIsFailClosedAndNotInAnyEdition() {
        val profile = TranslationProviderProfiles.hyMt2StqCandidate
        val admission = checkNotNull(profile.admission)

        assertEquals(TranslationProviderAvailability.EVALUATION_BLOCKED, profile.availability)
        assertEquals("hymt2-stq-2026-07-30-xiaomi15pro-android16", admission.candidateId)
        assertEquals(GeneratedTranslationAdmissionEvidence.SHA256, admission.canonicalSha256)
        assertFalse(admission.runtimeGateSatisfied)
        assertFalse(admission.isSatisfied)
        assertFalse(profile.isSelectable)
        assertFalse(TranslationProviderProfiles.editionProfiles.contains(profile))
        assertTrue(MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED in admission.failures)
        assertTrue(MiddleTierAdmissionFailure.SCORE_ARTIFACT_NOT_VERIFIED in admission.failures)
        assertTrue(MiddleTierAdmissionFailure.INTEGRATED_RELEASE_MEASUREMENT_MISSING in admission.failures)
    }

    @Test
    fun canonicalAdmissionFailuresCannotBeClearedThroughAMutableCast() {
        val admission = checkNotNull(TranslationProviderProfiles.hyMt2StqCandidate.admission)

        try {
            @Suppress("UNCHECKED_CAST")
            (admission.failures as MutableSet<MiddleTierAdmissionFailure>).clear()
            fail("Canonical admission failures were mutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: the public view is defensively copied and unmodifiable.
        }

        assertFalse(admission.isSatisfied)
        assertTrue(admission.failures.isNotEmpty())
    }

    @Test
    fun changingBlockedCandidateAvailabilityDoesNotDetachAdmission() {
        val copied = TranslationProviderProfiles.hyMt2StqCandidate.copy(
            availability = TranslationProviderAvailability.EXPERIMENTAL,
        )

        assertFalse(copied.isSelectable)
        assertSame(TranslationProviderProfiles.hyMt2StqCandidate.admission, copied.admission)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stqCandidateCopyCannotDropItsAdmissionRecord() {
        TranslationProviderProfiles.hyMt2StqCandidate.copy(
            availability = TranslationProviderAvailability.EXPERIMENTAL,
            admission = null,
        )
    }

    @Test
    fun incompleteMeasurementsAreTypedAsNullAndFailPublishedPolicy() {
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val incomplete = MiddleTierCandidateMeasurement(
            routeMeasurements = policy.requiredRoutes.map { route ->
                MiddleTierRouteMeasurement(
                    route = route,
                    q4BleuRetentionPercent = null,
                    criticalEvaluatedIds = null,
                    criticalRegressedIds = null,
                    rawMedianLatencyMillis = null,
                    appPipeline = MiddleTierAppPipelineMeasurement(null, null, null),
                )
            },
            integratedRelease = MiddleTierIntegratedReleaseMeasurement(
                processPssBytes = null,
                processHighWaterBytes = null,
                lmkEventCount = null,
                sustainedHotRunMinutes = null,
                thermalSampleIntervalSeconds = null,
                thermalStatusSamples = null,
            ),
        )
        val bindings = TranslationAdmissionArtifactBindings(
            corpusVerified = false,
            sourceModelVerified = false,
            runnableModelVerified = false,
            transformationManifestVerified = false,
            apkVerified = false,
            signerVerified = false,
            deviceRomVerified = false,
            scoreVerifiedByRoute = emptyMap(),
        )

        val failures = policy.evaluate(
            measurement = incomplete,
            runtimeGateSatisfied = false,
            bindings = bindings,
            integratedSummaryVerified = false,
        )

        assertTrue(MiddleTierAdmissionFailure.QUALITY_MEASUREMENT_MISSING in failures)
        assertTrue(MiddleTierAdmissionFailure.CRITICAL_CHECK_IDS_MISSING in failures)
        assertTrue(MiddleTierAdmissionFailure.RAW_LATENCY_MEASUREMENT_MISSING in failures)
        assertTrue(MiddleTierAdmissionFailure.APP_PIPELINE_MEASUREMENT_MISSING in failures)
        assertTrue(MiddleTierAdmissionFailure.PROCESS_PSS_MEASUREMENT_MISSING in failures)
        assertTrue(MiddleTierAdmissionFailure.THERMAL_CADENCE_MISSING in failures)
    }

    @Test
    fun policyRejectsMissingDuplicateAndUnexpectedRoutes() {
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val en = emptyRoute(TranslationRoute("en", "zh"))
        val ja = emptyRoute(TranslationRoute("ja", "zh"))
        val extra = emptyRoute(TranslationRoute("ko", "zh"))
        val emptyIntegrated = MiddleTierIntegratedReleaseMeasurement(null, null, null, null, null, null)
        val noTrust = TranslationAdmissionArtifactBindings(
            false, false, false, false, false, false, false, emptyMap(),
        )

        assertTrue(
            MiddleTierAdmissionFailure.REQUIRED_ROUTE_MISSING in policy.evaluate(
                MiddleTierCandidateMeasurement(listOf(en), emptyIntegrated),
                false,
                noTrust,
                false,
            ),
        )
        assertTrue(
            MiddleTierAdmissionFailure.DUPLICATE_ROUTE_MEASUREMENT in policy.evaluate(
                MiddleTierCandidateMeasurement(listOf(en, en, ja), emptyIntegrated),
                false,
                noTrust,
                false,
            ),
        )
        assertTrue(
            MiddleTierAdmissionFailure.UNEXPECTED_ROUTE_MEASUREMENT in policy.evaluate(
                MiddleTierCandidateMeasurement(listOf(en, ja, extra), emptyIntegrated),
                false,
                noTrust,
                false,
            ),
        )
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

    private fun emptyRoute(route: TranslationRoute): MiddleTierRouteMeasurement =
        MiddleTierRouteMeasurement(
            route = route,
            q4BleuRetentionPercent = null,
            criticalEvaluatedIds = null,
            criticalRegressedIds = null,
            rawMedianLatencyMillis = null,
            appPipeline = MiddleTierAppPipelineMeasurement(null, null, null),
        )
}
