package com.screentranslation.app.ml

import com.screentranslation.app.BuildConfig
import java.io.File
import java.security.MessageDigest
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
        assertEquals(
            BergamotLiteProviderContract.modelIdsByRoute.keys,
            TranslationProviderProfiles.bergamotLite.evaluatedRoutes,
        )
        assertEquals(
            35.547,
            TranslationProviderProfiles.bergamotLite.performance.routeObservations
                .getValue(TranslationRoute("en", "zh"))
                .rawMedianLatencyMillis,
            0.0,
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
        assertEquals(
            TranslationProviderAvailability.EXPERIMENTAL,
            profile.availability,
        )
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
    fun currentOpenStqGateIsFailClosedAndEvidencePinned() {
        val profile = TranslationProviderProfiles.hyMt2StqCandidate
        val gate = checkNotNull(profile.evaluationGate)

        assertEquals(TranslationProviderAvailability.EVALUATION_BLOCKED, profile.availability)
        assertEquals(UpstreamPullRequestState.OPEN, gate.upstreamPullRequestState)
        assertEquals(
            "caa596ab3f0f8768ee326d6e3d5d39782194676c",
            gate.pinnedRuntimeCommit,
        )
        assertEquals(RuntimeSupportVerificationStatus.UNVERIFIED, gate.runtimeSupportVerificationStatus)
        assertNull(gate.runtimeSupportEvidence)
        assertFalse(profile.isSelectable)
        assertFalse(gate.isSatisfied)
        assertEquals(
            setOf(
                "UPSTREAM_PULL_REQUEST_MERGED",
                "UPSTREAM_MERGE_COMMIT_RECORDED",
                "PINNED_RUNTIME_CONTAINS_MERGE_VERIFIED",
            ),
            gate.unmetRequirements,
        )
        assertEquals(
            "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
            gate.sourceModelSha256,
        )
        assertEquals(
            "e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7",
            gate.runnableModelSha256,
        )
        assertEquals("retag-legacy-stq-gguf-v1", gate.modelTransformationId)
        assertFalse(TranslationProviderProfiles.editionProfiles.contains(profile))
        assertEquals(MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER, profile.middleTierAdmissionPolicy)
    }

    @Test
    fun profilePinsMachineReadableStqEvidenceFile() {
        val gate = checkNotNull(TranslationProviderProfiles.hyMt2StqCandidate.evaluationGate)
        val evidence = repositoryFile("docs/evidence/hymt2-stq-evidence-2026-07-30.json")
        val evidenceText = evidence.readText()

        assertEquals(gate.evaluationEvidenceSha256, sha256(evidence))
        assertTrue(evidenceText.contains("\"evidence_id\": \"${gate.evaluationEvidenceId}\""))
        assertTrue(evidenceText.contains("\"snapshot_state\": \"OPEN\""))
        assertTrue(evidenceText.contains(checkNotNull(gate.pinnedRuntimeCommit)))
        assertTrue(evidenceText.contains(gate.sourceModelSha256))
        assertTrue(evidenceText.contains(gate.runnableModelSha256))
        assertTrue(evidenceText.contains(gate.modelTransformationManifestSha256))
        assertTrue(evidenceText.contains("\"status\": \"NOT_MEASURED\""))
    }

    @Test
    fun openOrClosedUnmergedPullRequestNeverSatisfiesGate() {
        val otherwiseVerified = verifiedGate()

        assertFalse(
            otherwiseVerified.copy(
                upstreamPullRequestState = UpstreamPullRequestState.OPEN,
            ).isSatisfied,
        )
        assertFalse(
            otherwiseVerified.copy(
                upstreamPullRequestState = UpstreamPullRequestState.CLOSED_UNMERGED,
            ).isSatisfied,
        )
    }

    @Test
    fun mergedStateWithoutMergeCommitNeverSatisfiesGate() {
        val gate = verifiedGate().copy(upstreamSupportMergeCommit = null)

        assertFalse(gate.isSatisfied)
        assertTrue("UPSTREAM_MERGE_COMMIT_RECORDED" in gate.unmetRequirements)
        assertTrue("PINNED_RUNTIME_CONTAINS_MERGE_VERIFIED" in gate.unmetRequirements)
    }

    @Test
    fun runtimeEvidenceMustProveMergeAncestryAndExactGitlink() {
        val gate = verifiedGate()
        val evidence = checkNotNull(gate.runtimeSupportEvidence)
        val otherRuntime = "76543210fedcba9876543210fedcba9876543210"

        assertFalse(
            gate.copy(
                runtimeSupportEvidence = evidence.copy(mergeCommitIsAncestorOfRuntime = false),
            ).isSatisfied,
        )
        assertFalse(
            gate.copy(
                runtimeSupportEvidence = evidence.copy(
                    observedRepositoryGitlinkCommit = otherRuntime,
                ),
            ).isSatisfied,
        )
    }

    @Test
    fun runtimeEvidenceMustVerifyExactRunnableModelHash() {
        val gate = verifiedGate()
        val evidence = checkNotNull(gate.runtimeSupportEvidence)

        assertFalse(
            gate.copy(
                runtimeSupportEvidence = evidence.copy(
                    verifiedRunnableModelSha256 = "0".repeat(64),
                ),
            ).isSatisfied,
        )
    }

    @Test
    fun selectabilityIsAlwaysBoundToEvaluationGate() {
        val unsatisfied = checkNotNull(TranslationProviderProfiles.hyMt2StqCandidate.evaluationGate)
        val satisfied = verifiedGate()

        assertFalse(
            TranslationProviderProfiles.bergamotLite.copy(
                evaluationGate = unsatisfied,
            ).isSelectable,
        )
        assertTrue(
            TranslationProviderProfiles.hyMt2Q4Full.copy(
                evaluationGate = satisfied,
            ).isSelectable,
        )
    }

    @Test
    fun currentStqEvidenceFailsRouteQualityAndMissingIntegrationEvidence() {
        val profile = TranslationProviderProfiles.hyMt2StqCandidate
        val failures = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER.evaluate(
            measurement = currentStqMeasurement(),
            evaluationGate = checkNotNull(profile.evaluationGate),
        )

        assertEquals(
            setOf(
                MiddleTierAdmissionFailure.RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED,
                MiddleTierAdmissionFailure.QUALITY_RETENTION_BELOW_THRESHOLD,
                MiddleTierAdmissionFailure.CRITICAL_CHECK_REGRESSION,
                MiddleTierAdmissionFailure.RAW_MEDIAN_LATENCY_ABOVE_THRESHOLD,
                MiddleTierAdmissionFailure.APP_PIPELINE_MEASUREMENT_MISSING,
                MiddleTierAdmissionFailure.INTEGRATED_RELEASE_MEASUREMENT_MISSING,
            ),
            failures,
        )
    }

    @Test
    fun policyRejectsMissingDuplicateAndUnexpectedRoutes() {
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val gate = verifiedGate()
        val en = passingRoute(TranslationRoute("en", "zh"))
        val ja = passingRoute(TranslationRoute("ja", "zh"))
        val integrated = passingIntegratedRelease(policy)

        assertEquals(
            setOf(MiddleTierAdmissionFailure.REQUIRED_ROUTE_MISSING),
            policy.evaluate(MiddleTierCandidateMeasurement(listOf(en), integrated), gate),
        )
        assertEquals(
            setOf(MiddleTierAdmissionFailure.DUPLICATE_ROUTE_MEASUREMENT),
            policy.evaluate(MiddleTierCandidateMeasurement(listOf(en, en, ja), integrated), gate),
        )
        assertEquals(
            setOf(MiddleTierAdmissionFailure.UNEXPECTED_ROUTE_MEASUREMENT),
            policy.evaluate(
                MiddleTierCandidateMeasurement(
                    listOf(en, ja, passingRoute(TranslationRoute("ko", "zh"))),
                    integrated,
                ),
                gate,
            ),
        )
    }

    @Test
    fun twoThermalEndpointsDoNotQualifyAsSustainedSampling() {
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val incompleteThermal = passingIntegratedRelease(policy).copy(
            thermalStatusSamples = listOf(0, 0),
        )

        assertEquals(
            setOf(MiddleTierAdmissionFailure.THERMAL_SAMPLING_INSUFFICIENT),
            policy.evaluate(
                MiddleTierCandidateMeasurement(
                    routeMeasurements = policy.requiredRoutes.map(::passingRoute),
                    integratedRelease = incompleteThermal,
                ),
                verifiedGate(),
            ),
        )
    }

    @Test
    fun candidateAtEveryPublishedBoundaryIsAdmittedOnlyWithTrustedEvidence() {
        val policy = MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER
        val failures = policy.evaluate(
            measurement = MiddleTierCandidateMeasurement(
                routeMeasurements = policy.requiredRoutes.map(::passingRoute),
                integratedRelease = passingIntegratedRelease(policy),
            ),
            evaluationGate = verifiedGate(),
        )

        assertTrue(verifiedGate().isSatisfied)
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

    private fun currentStqMeasurement(): MiddleTierCandidateMeasurement =
        MiddleTierCandidateMeasurement(
            routeMeasurements = listOf(
                MiddleTierRouteMeasurement(
                    route = TranslationRoute("en", "zh"),
                    q4BleuRetentionPercent = 88.37,
                    criticalCheckRegressionsAgainstShippingLite = 0,
                    rawMedianLatencyMillis = 615.727,
                    appPipeline = MiddleTierAppPipelineMeasurement(
                        medianLatencyMillis = 600.994,
                        p95LatencyMillis = 1_432.453,
                        timeoutCount = null,
                    ),
                ),
                MiddleTierRouteMeasurement(
                    route = TranslationRoute("ja", "zh"),
                    q4BleuRetentionPercent = 89.68,
                    criticalCheckRegressionsAgainstShippingLite = 8,
                    rawMedianLatencyMillis = 622.125,
                    appPipeline = MiddleTierAppPipelineMeasurement(
                        medianLatencyMillis = 577.303,
                        p95LatencyMillis = 1_416.108,
                        timeoutCount = null,
                    ),
                ),
            ),
            integratedRelease = null,
        )

    private fun passingRoute(route: TranslationRoute): MiddleTierRouteMeasurement =
        MiddleTierRouteMeasurement(
            route = route,
            q4BleuRetentionPercent = 95.0,
            criticalCheckRegressionsAgainstShippingLite = 0,
            rawMedianLatencyMillis = 349.999,
            appPipeline = MiddleTierAppPipelineMeasurement(
                medianLatencyMillis = 749.999,
                p95LatencyMillis = 1_499.999,
                timeoutCount = 0,
            ),
        )

    private fun passingIntegratedRelease(
        policy: MiddleTierAdmissionPolicy,
    ): MiddleTierIntegratedReleaseMeasurement = MiddleTierIntegratedReleaseMeasurement(
        processPssBytes = policy.maximumIntegratedProcessPssBytesExclusive - 1L,
        processHighWaterBytes = policy.maximumIntegratedProcessHighWaterBytesExclusive - 1L,
        lmkEventCount = 0,
        sustainedHotRunMinutes = policy.minimumSustainedHotRunMinutes,
        thermalStatusSamples = List(policy.minimumThermalStatusSampleCount) {
            policy.maximumThermalStatus
        },
    )

    private fun verifiedGate(): TranslationEvaluationGate {
        val open = checkNotNull(TranslationProviderProfiles.hyMt2StqCandidate.evaluationGate)
        val mergeCommit = "0123456789abcdef0123456789abcdef01234567"
        val runtimeCommit = "89abcdef0123456789abcdef0123456789abcdef"
        val evidence = TranslationRuntimeSupportEvidence(
            kind = RuntimeSupportEvidenceKind.CI_MERGE_ANCESTRY,
            verifiedUpstreamMergeCommit = mergeCommit,
            verifiedPinnedRuntimeCommit = runtimeCommit,
            observedRepositoryGitlinkCommit = runtimeCommit,
            mergeCommitIsAncestorOfRuntime = true,
            verifiedRunnableModelSha256 = open.runnableModelSha256,
            evidenceReference = "https://github.com/Arimacose/ScreenTranslation/actions/runs/123456",
        )
        return open.copy(
            upstreamPullRequestState = UpstreamPullRequestState.MERGED,
            upstreamSupportMergeCommit = mergeCommit,
            pinnedRuntimeCommit = runtimeCommit,
            runtimeSupportVerificationStatus =
                RuntimeSupportVerificationStatus.VERIFIED_CONTAINS_UPSTREAM_MERGE,
            runtimeSupportEvidence = evidence,
        )
    }

    private fun repositoryFile(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("..", relativePath),
    ).firstOrNull(File::isFile) ?: error("Repository file not found: $relativePath")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
