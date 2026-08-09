package com.screentranslation.app.ml

import com.screentranslation.app.RetainedModelReadiness
import com.screentranslation.app.retainedReadinessMatches
import com.screentranslation.llama.LlamaRuntime
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMt2Q4TranslationEngineTest {
    @Test
    fun promptRequestsChineseAndTranslationOnly() {
        val source = "Although the weather changed, the expedition continued."

        val prompt = HyMt2Q4Prompt.build(source)

        assertTrue(prompt.contains("into Chinese"))
        assertTrue(prompt.contains("only output the translated result"))
        assertTrue(prompt.endsWith(source))
    }

    @Test
    fun cleanRemovesKnownTrailingMarkersRepeatedly() {
        val cleaned = HyMt2Q4Prompt.clean(
            "探险仍在继续。[end of text] <|endoftext|> <?hy_end?of?sentence?>",
        )

        assertEquals("探险仍在继续。", cleaned)
    }

    @Test
    fun modelCoordinatesArePinnedAndSelfConsistent() {
        assertEquals(40, HyMt2Q4ModelDescriptor.MODEL_REVISION.length)
        assertEquals(64, HyMt2Q4ModelDescriptor.MODEL_SHA256.length)
        assertTrue(HyMt2Q4ModelDescriptor.MODEL_REVISION.all { it.isLowerCaseHex() })
        assertTrue(HyMt2Q4ModelDescriptor.MODEL_SHA256.all { it.isLowerCaseHex() })
        assertTrue(
            HyMt2Q4ModelDescriptor.MODEL_URL.contains(
                "/resolve/${HyMt2Q4ModelDescriptor.MODEL_REVISION}/",
            ),
        )
        assertTrue(
            HyMt2Q4ModelDescriptor.MODEL_URL.endsWith(
                "/${HyMt2Q4ModelDescriptor.MODEL_FILE_NAME}?download=true",
            ),
        )
        assertEquals(1_133_080_448L, HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES)
        assertFalse(HyMt2Q4ModelDescriptor.MODEL_URL.contains("/main/"))
    }

    @Test
    fun runtimeModelStorageAndCloseContractsMatchFullProfile() {
        val profile = TranslationProviderProfiles.hyMt2Q4Full
        val descriptor = checkNotNull(profile.modelStorage.localModelDescriptor)

        assertEquals(HyMt2Q4ProviderContract.modelDescriptor, descriptor)
        assertEquals(descriptor.revision, HyMt2Q4ModelDescriptor.MODEL_REVISION)
        assertEquals(descriptor.fileName, HyMt2Q4ModelDescriptor.MODEL_FILE_NAME)
        assertEquals(descriptor.relativeDirectory, HyMt2Q4ModelDescriptor.MODEL_RELATIVE_DIRECTORY)
        assertEquals("models/hymt2-q4", descriptor.relativeDirectory)
        assertEquals(descriptor.expectedBytes, HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES)
        assertEquals(descriptor.sha256, HyMt2Q4ModelDescriptor.MODEL_SHA256)
        assertEquals(
            HyMt2Q4ProviderContract.CONTEXT_WINDOW_TOKENS,
            LlamaRuntime.DEFAULT_CONTEXT_SIZE,
        )
        assertEquals(
            HyMt2Q4ProviderContract.RESERVED_OUTPUT_TOKENS,
            LlamaRuntime.DEFAULT_MAX_TOKENS,
        )
        assertTrue(profile.modelStorage.userRemovableFromApp)
        assertEquals(
            TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            profile.cancellation.perRequest,
        )
        assertEquals(
            TranslationCloseBehavior.MARK_CLOSED_DRAIN_EXECUTOR_THEN_RELEASE_RUNTIME,
            profile.cancellation.onClose,
        )
    }

    @Test
    fun exactSizePartialIsHashedInsteadOfResumedFromEof() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { file ->
            assertEquals(
                HyMt2Q4CandidateState.VERIFIED_COMPLETE,
                classifyHyMt2Q4Candidate(
                    file = file,
                    expectedSize = file.length(),
                    expectedSha256 = sha256(file),
                    allowResume = true,
                    sha256 = ::sha256,
                ),
            )
        }
    }

    @Test
    fun corruptExactSizeCandidateIsInvalidEvenWhenResumeIsAllowed() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { file ->
            assertEquals(
                HyMt2Q4CandidateState.INVALID,
                classifyHyMt2Q4Candidate(
                    file = file,
                    expectedSize = file.length(),
                    expectedSha256 = "0".repeat(64),
                    allowResume = true,
                    sha256 = ::sha256,
                ),
            )
        }
    }

    @Test
    fun coldReadinessRequiresTheFullModelHashNotOnlyItsSize() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { file ->
            assertTrue(
                isHyMt2Q4ModelPrepared(
                    file = file,
                    expectedSize = file.length(),
                    expectedSha256 = sha256(file),
                    sha256 = ::sha256,
                ),
            )
            assertFalse(
                isHyMt2Q4ModelPrepared(
                    file = file,
                    expectedSize = file.length(),
                    expectedSha256 = "0".repeat(64),
                    sha256 = ::sha256,
                ),
            )
        }
    }

    @Test
    fun canonicalVerifierHashesTheModelAndTreatsMarkerAsAuditOnly() {
        withTempDirectory { directory ->
            val model = File(directory, "model.gguf").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val marker = File(directory, "model.gguf.sha256")
            val expectedSha256 = sha256(model)

            val missingMarker = HyMt2Q4ModelVerifier.verify(
                modelFile = model,
                markerFile = marker,
                expectedSize = model.length(),
                expectedSha256 = expectedSha256,
            )
            assertTrue(missingMarker.ready)
            assertTrue(missingMarker.identity != null)
            assertFalse(missingMarker.markerMatches)

            marker.writeText("0".repeat(64))
            val staleMarker = HyMt2Q4ModelVerifier.verify(
                modelFile = model,
                markerFile = marker,
                expectedSize = model.length(),
                expectedSha256 = expectedSha256,
            )
            assertTrue(staleMarker.ready)
            assertFalse(staleMarker.markerMatches)
        }
    }

    @Test
    fun matchingMarkerCannotMakeCorruptSameSizeModelReady() {
        withTempDirectory { directory ->
            val model = File(directory, "model.gguf").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val marker = File(directory, "model.gguf.sha256")
            val expectedSha256 = sha256(model)
            marker.writeText(expectedSha256)
            model.writeBytes(byteArrayOf(4, 3, 2, 1))

            val verification = HyMt2Q4ModelVerifier.verify(
                modelFile = model,
                markerFile = marker,
                expectedSize = model.length(),
                expectedSha256 = expectedSha256,
            )

            assertFalse(verification.ready)
            assertTrue(verification.markerMatches)
            assertTrue(verification.identity == null)
        }
    }

    @Test
    fun retainedFullReadinessIsRejectedAfterModelDeletion() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { model ->
            val expectedSha256 = sha256(model)
            val identity = HyMt2Q4ModelVerifier.currentIdentity(
                modelFile = model,
                expectedSize = model.length(),
                expectedSha256 = expectedSha256,
            )!!
            val pair = "en" to "zh"
            val retained = RetainedModelReadiness(pair, identity, generation = 1L)

            assertTrue(model.delete())
            val currentIdentity = HyMt2Q4ModelVerifier.currentIdentity(
                modelFile = model,
                expectedSize = 4L,
                expectedSha256 = expectedSha256,
            )
            assertFalse(retainedReadinessMatches(retained, pair, currentIdentity))
        }
    }

    @Test
    fun verifiedFullIdentityIsNotReusedAfterArtifactDeletionOrReplacement() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { model ->
            val expectedSha256 = sha256(model)
            val verified = HyMt2Q4ModelVerifier.verify(
                modelFile = model,
                markerFile = null,
                expectedSize = model.length(),
                expectedSha256 = expectedSha256,
            ).identity!!

            assertTrue(model.delete())
            assertNull(
                resolveCurrentPreparationIdentity(
                    verified,
                    HyMt2Q4ModelVerifier.currentIdentity(model, 4L, expectedSha256),
                ),
            )

            model.writeBytes(byteArrayOf(4, 3, 2, 1))
            assertNull(
                resolveCurrentPreparationIdentity(
                    verified,
                    HyMt2Q4ModelVerifier.currentIdentity(model, 4L, expectedSha256),
                ),
            )
        }
    }

    @Test
    fun canonicalHashVerificationHonorsLifecycleCancellation() {
        withTempFile(ByteArray(2 * 1024 * 1024) { it.toByte() }) { model ->
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                HyMt2Q4ModelVerifier.verify(
                    modelFile = model,
                    markerFile = null,
                    expectedSize = model.length(),
                    expectedSha256 = sha256(model),
                    checkActive = {
                        checks += 1
                        check(checks < 4) { "Activity stopped" }
                    },
                )
            }
            assertTrue(checks >= 4)
        }
    }

    @Test
    fun onlyShortPartialIsResumable() {
        withTempFile(byteArrayOf(1, 2, 3, 4)) { file ->
            var hashCalls = 0
            val state = classifyHyMt2Q4Candidate(
                file = file,
                expectedSize = file.length() + 1,
                expectedSha256 = "unused",
                allowResume = true,
                sha256 = {
                    hashCalls += 1
                    "unused"
                },
            )

            assertEquals(HyMt2Q4CandidateState.RESUMABLE, state)
            assertEquals(0, hashCalls)
            assertEquals(
                HyMt2Q4CandidateState.INVALID,
                classifyHyMt2Q4Candidate(
                    file = file,
                    expectedSize = file.length() + 1,
                    expectedSha256 = "unused",
                    allowResume = false,
                    sha256 = { "unused" },
                ),
            )
        }
    }

    @Test
    fun runtimePoolSharesOneRuntimeUntilLastLeaseCloses() {
        withTempFile(byteArrayOf(1)) { model ->
            val fake = FakeRuntimeHandle()
            val pool = HyMt2Q4RuntimePool { fake }

            val first = pool.acquire(model)
            val second = pool.acquire(model)
            assertEquals(1, fake.loadCount)

            first.close()
            assertEquals(0, fake.closeCount)
            assertEquals("translated:prompt", second.complete("prompt"))

            second.close()
            assertEquals(1, fake.closeCount)
        }
    }

    @Test
    fun staleLeaseCloseDoesNotReleaseNewRuntimeGeneration() {
        withTempFile(byteArrayOf(1)) { model ->
            val runtimes = mutableListOf<FakeRuntimeHandle>()
            val pool = HyMt2Q4RuntimePool {
                FakeRuntimeHandle().also(runtimes::add)
            }

            val stale = pool.acquire(model)
            stale.close()
            val current = pool.acquire(model)
            stale.close()

            assertEquals(2, runtimes.size)
            assertEquals(0, runtimes[1].closeCount)
            assertEquals("translated:current", current.complete("current"))
            current.close()
            assertEquals(1, runtimes[1].closeCount)
        }
    }

    @Test
    fun runtimePoolRejectsDifferentModelWhileLeaseIsActive() {
        withTempFile(byteArrayOf(1)) { firstModel ->
            withTempFile(byteArrayOf(2)) { secondModel ->
                val pool = HyMt2Q4RuntimePool { FakeRuntimeHandle() }
                val lease = pool.acquire(firstModel)
                try {
                    assertThrows(IllegalStateException::class.java) {
                        pool.acquire(secondModel)
                    }
                } finally {
                    lease.close()
                }
            }
        }
    }

    private fun withTempFile(bytes: ByteArray, assertion: (File) -> Unit) {
        val file = File.createTempFile("hymt2-candidate-", ".part")
        try {
            file.writeBytes(bytes)
            assertion(file)
        } finally {
            file.delete()
        }
    }

    private fun withTempDirectory(assertion: (File) -> Unit) {
        val directory = createTempDirectory("hymt2-verifier-").toFile()
        try {
            assertion(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private class FakeRuntimeHandle : HyMt2Q4RuntimeHandle {
        var loadCount = 0
        var closeCount = 0

        override fun loadModel(model: File): String {
            loadCount += 1
            return "fake:${model.name}"
        }

        override fun complete(prompt: String): String = "translated:$prompt"

        override fun close() {
            closeCount += 1
        }
    }

    private fun Char.isLowerCaseHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f'
}
