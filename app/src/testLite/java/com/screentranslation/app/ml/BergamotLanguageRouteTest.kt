package com.screentranslation.app.ml

import com.screentranslation.app.RetainedModelReadiness
import com.screentranslation.app.retainedReadinessMatches
import com.screentranslation.app.model.ModelDownloadState
import com.screentranslation.app.model.resolveBergamotModelDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.Reader
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BergamotLanguageRouteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun englishToChineseUsesDirectModel() {
        val route = BergamotLanguageRoute.requireSupported("EN", "zh")

        assertEquals(listOf("en-zh"), route.modelIds)
    }

    @Test
    fun japaneseToChineseUsesMeasuredCascade() {
        val route = BergamotLanguageRoute.requireSupported("ja", "ZH")

        assertEquals(listOf("ja-en", "en-zh"), route.modelIds)
    }

    @Test
    fun runtimeRouteAndCloseContractsMatchLiteProfile() {
        val profile = TranslationProviderProfiles.bergamotLite

        BergamotLiteProviderContract.modelIdsByRoute.forEach { (declaredRoute, modelIds) ->
            val runtimeRoute = BergamotLanguageRoute.requireSupported(
                declaredRoute.sourceLanguageTag,
                declaredRoute.targetLanguageTag,
            )
            assertEquals(modelIds, runtimeRoute.modelIds)
            assertEquals(declaredRoute, profile.languages.routeFor(
                runtimeRoute.sourceLanguage,
                runtimeRoute.targetLanguage,
            ))
        }
        assertEquals(
            TranslationPerRequestCancellation.NO_PER_REQUEST_CANCEL,
            profile.cancellation.perRequest,
        )
        assertEquals(
            TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
            profile.cancellation.onClose,
        )
    }

    @Test
    fun unsupportedPairNamesLiteAndSupportedRoutes() {
        val error = runCatching {
            BergamotLanguageRoute.requireSupported("ko", "zh")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Lite / Bergamot"))
        assertTrue(error?.message.orEmpty().contains("en→zh"))
        assertTrue(error?.message.orEmpty().contains("ja→en→zh"))
    }

    @Test
    fun cachedFileContentMustMatchHashEvenWhenMarkerMatches() {
        val file = temporaryFolder.newFile("model.bin")
        val marker = temporaryFolder.newFile("model.bin.sha256")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val expectedHash = sha256(file.readBytes())
        marker.writeText(expectedHash)

        assertTrue(
            BergamotCachedFileVerifier.isReusable(
                file,
                marker,
                file.length(),
                expectedHash,
            ),
        )

        file.writeBytes(byteArrayOf(4, 3, 2, 1))
        assertFalse(
            BergamotCachedFileVerifier.isReusable(
                file,
                marker,
                file.length(),
                expectedHash,
            ),
        )
    }

    @Test
    fun coldReadinessChecksEveryPinnedFileInTheSelectedRoute() {
        val root = temporaryFolder.newFolder("bergamot-ready")
        val route = BergamotLanguageRoute.requireSupported("ja", "zh")
        route.modelIds.forEach { File(root, it).mkdirs() }
        val checked = mutableListOf<String>()

        val ready = isBergamotRoutePreparedAndStable(
            root = root,
            route = route,
            preparationIdentityProvider = { "unchanged-route" },
            fileVerifier = { file, marker, expectedSize, expectedSha256, _ ->
                checked += file.name
                assertEquals("${file.name}.sha256", marker.name)
                assertTrue(expectedSize > 0L)
                assertEquals(64, expectedSha256.length)
                true
            },
        )

        assertTrue(ready)
        assertEquals(
            route.modelIds
                .flatMap { id ->
                    bergamotModelSpecs().first { it.id == id }.files.map { it.outputName }
                }
                .toSet(),
            checked.toSet(),
        )
    }

    @Test
    fun coldReadinessFailsWhenAnyCascadedModelFileFailsVerification() {
        val root = temporaryFolder.newFolder("bergamot-corrupt")
        val route = BergamotLanguageRoute.requireSupported("ja", "zh")
        route.modelIds.forEach { File(root, it).mkdirs() }

        assertFalse(
            isBergamotRoutePreparedAndStable(
                root = root,
                route = route,
                preparationIdentityProvider = { "unchanged-route" },
                fileVerifier = { file, _, _, _, _ ->
                    file.name != "model.jaen.intgemm.alphas.bin"
                },
            ),
        )
    }

    @Test
    fun coldReadinessRejectsRouteChangedDuringFullHashing() {
        val root = temporaryFolder.newFolder("bergamot-toctou")
        val route = BergamotLanguageRoute.requireSupported("en", "zh")
        route.modelIds.forEach { File(root, it).mkdirs() }
        var identityReads = 0
        var verifiedFiles = 0

        val ready = isBergamotRoutePreparedAndStable(
            root = root,
            route = route,
            preparationIdentityProvider = {
                identityReads += 1
                if (identityReads == 1) "before-full-hash" else "after-full-hash"
            },
            fileVerifier = { _, _, _, _, _ ->
                verifiedFiles += 1
                true
            },
        )

        assertFalse(ready)
        assertTrue(verifiedFiles > 0)
        assertEquals(2, identityReads)
    }

    @Test
    fun canonicalModelVerifierRejectsInventoryArtifactChangedDuringHashing() {
        val root = temporaryFolder.newFolder("bergamot-model-toctou")
        val model = BergamotModelSpec(
            id = "test-model",
            baseUrl = "https://HOST/models/test/exported",
            files = listOf(
                BergamotFileSpec(
                    compressedName = "model.bin.gz",
                    compressedSize = 4L,
                    compressedSha256 = "0".repeat(64),
                    outputName = "model.bin",
                    outputSize = 4L,
                    outputSha256 = "1".repeat(64),
                ),
            ),
            configText = "models: []\n",
        )
        File(root, model.id).mkdirs()
        var identityReads = 0

        val ready = isBergamotModelPreparedAndStable(
            root = root,
            model = model,
            preparationIdentityProvider = {
                identityReads += 1
                if (identityReads == 1) "before-full-hash" else "after-full-hash"
            },
            fileVerifier = { _, _, _, _, _ -> true },
        )

        assertFalse(ready)
        assertEquals(2, identityReads)
    }

    @Test
    fun retainedIdentityRejectsDeletedOrSameSizeReplacedModelOutput() {
        val root = temporaryFolder.newFolder("bergamot-identity")
        val route = BergamotLanguageRoute.requireSupported("en", "zh")
        var fileNumber = 0
        route.modelIds.forEach { modelId ->
            val directory = File(root, modelId).apply { mkdirs() }
            val model = bergamotModelSpecs().first { it.id == modelId }
            model.files.forEach { spec ->
                File(directory, spec.outputName).writeBytes(
                    ByteArray(32) { offset -> (fileNumber + offset).toByte() },
                )
                File(directory, "${spec.outputName}.sha256").writeText(
                    "${spec.outputSha256}\n",
                )
                fileNumber += 1
            }
        }
        val testFileIdentity = { file: File, _: Long ->
            file.preparationFileIdentity(file.length())
        }
        val initialIdentity = bergamotRoutePreparationIdentity(
            root = root,
            route = route,
            fileIdentity = testFileIdentity,
        ) ?: error("test route identity was not created")
        val selectedPair = route.sourceLanguage to route.targetLanguage
        val retained = RetainedModelReadiness(
            pair = selectedPair,
            identity = initialIdentity,
            generation = 1L,
        )
        assertTrue(retainedReadinessMatches(retained, selectedPair, initialIdentity))

        val firstSpec = bergamotModelSpecs()
            .first { it.id == route.modelIds.first() }
            .files.first()
        val victim = File(File(root, route.modelIds.first()), firstSpec.outputName)
        val originalSize = victim.length()
        assertTrue(victim.delete())
        val deletedIdentity = bergamotRoutePreparationIdentity(
            root = root,
            route = route,
            fileIdentity = testFileIdentity,
        )
        assertNull(deletedIdentity)
        assertFalse(retainedReadinessMatches(retained, selectedPair, deletedIdentity))
        assertNull(resolveCurrentPreparationIdentity(initialIdentity, deletedIdentity))

        victim.writeBytes(ByteArray(originalSize.toInt()) { 0x5a.toByte() })
        val replacedIdentity = bergamotRoutePreparationIdentity(
            root = root,
            route = route,
            fileIdentity = testFileIdentity,
        ) ?: error("replacement route identity was not created")
        assertNotEquals(initialIdentity, replacedIdentity)
        assertFalse(retainedReadinessMatches(retained, selectedPair, replacedIdentity))
        assertNull(resolveCurrentPreparationIdentity(initialIdentity, replacedIdentity))
    }

    @Test
    fun retainedIdentityRequiresThePinnedHashMarker() {
        val root = temporaryFolder.newFolder("bergamot-marker-identity")
        val route = BergamotLanguageRoute.requireSupported("en", "zh")
        route.modelIds.forEach { modelId ->
            val directory = File(root, modelId).apply { mkdirs() }
            val model = bergamotModelSpecs().first { it.id == modelId }
            model.files.forEach { spec ->
                File(directory, spec.outputName).writeBytes(byteArrayOf(1, 2, 3, 4))
                File(directory, "${spec.outputName}.sha256").writeText(spec.outputSha256)
            }
        }
        val identityProvider = { file: File, _: Long ->
            file.preparationFileIdentity(file.length())
        }
        assertTrue(
            bergamotRoutePreparationIdentity(root, route, fileIdentity = identityProvider) != null,
        )

        val firstSpec = bergamotModelSpecs()
            .first { it.id == route.modelIds.first() }
            .files.first()
        File(
            File(root, route.modelIds.first()),
            "${firstSpec.outputName}.sha256",
        ).writeText("not-the-pinned-hash")

        assertNull(
            bergamotRoutePreparationIdentity(root, route, fileIdentity = identityProvider),
        )
    }

    @Test
    fun modelInventoryUsesCanonicalHashAndRejectsSameSizeCorruption() {
        val root = temporaryFolder.newFolder("bergamot-inventory")
        val validBytes = byteArrayOf(1, 3, 3, 7)
        val expectedHash = sha256(validBytes)
        val spec = BergamotModelSpec(
            id = "test-model",
            baseUrl = "https://HOST/models/test/exported",
            files = listOf(
                BergamotFileSpec(
                    compressedName = "model.bin.gz",
                    compressedSize = validBytes.size.toLong(),
                    compressedSha256 = expectedHash,
                    outputName = "model.bin",
                    outputSize = validBytes.size.toLong(),
                    outputSha256 = expectedHash,
                ),
            ),
            configText = "models: []\n",
        )
        val directory = File(root, spec.id).apply { mkdirs() }
        val output = File(directory, spec.files.single().outputName).apply {
            writeBytes(validBytes)
        }
        File(directory, "${spec.files.single().outputName}.sha256").writeText(expectedHash)

        // decoder.yml is a regenerable derivative and is intentionally absent.
        assertEquals(
            ModelDownloadState.READY,
            resolveBergamotModelDownloadState(root, spec, hasPartial = false),
        )

        output.writeBytes(byteArrayOf(7, 3, 3, 1))

        assertEquals(
            ModelDownloadState.NOT_DOWNLOADED,
            resolveBergamotModelDownloadState(root, spec, hasPartial = false),
        )
    }

    @Test
    fun modelInventoryCancellationInterruptsCanonicalHashing() {
        val root = temporaryFolder.newFolder("bergamot-inventory-cancel")
        val bytes = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val expectedHash = sha256(bytes)
        val spec = BergamotModelSpec(
            id = "cancelled-model",
            baseUrl = "https://HOST/models/cancelled/exported",
            files = listOf(
                BergamotFileSpec(
                    compressedName = "model.bin.gz",
                    compressedSize = bytes.size.toLong(),
                    compressedSha256 = expectedHash,
                    outputName = "model.bin",
                    outputSize = bytes.size.toLong(),
                    outputSha256 = expectedHash,
                ),
            ),
            configText = "models: []\n",
        )
        val directory = File(root, spec.id).apply { mkdirs() }
        File(directory, spec.files.single().outputName).writeBytes(bytes)
        File(directory, "${spec.files.single().outputName}.sha256").writeText(expectedHash)
        var checks = 0

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            isBergamotModelPreparedAndStable(
                root = root,
                model = spec,
                checkOpen = {
                    checks += 1
                    // Calls 1-3 cover the stable-verifier and per-file setup;
                    // call 4 enters the first 1 MiB hash chunk and call 5
                    // proves cancellation is polled again before the next chunk.
                    check(checks < 5) { "inventory stopped during hash" }
                },
                preparationIdentityProvider = { "stable-artifact" },
            )
        }
        assertEquals(5, checks)
    }

    @Test
    fun canonicalReadyStateWinsOverStalePartialArtifact() {
        val root = temporaryFolder.newFolder("bergamot-ready-with-stale-part")
        val bytes = byteArrayOf(2, 0, 2, 6)
        val expectedHash = sha256(bytes)
        val spec = BergamotModelSpec(
            id = "ready-model",
            baseUrl = "https://HOST/models/ready/exported",
            files = listOf(
                BergamotFileSpec(
                    compressedName = "model.bin.gz",
                    compressedSize = bytes.size.toLong(),
                    compressedSha256 = expectedHash,
                    outputName = "model.bin",
                    outputSize = bytes.size.toLong(),
                    outputSha256 = expectedHash,
                ),
            ),
            configText = "models: []\n",
        )
        val directory = File(root, spec.id).apply { mkdirs() }
        File(directory, spec.files.single().outputName).writeBytes(bytes)
        File(directory, "${spec.files.single().outputName}.sha256").writeText(expectedHash)
        File(directory, "${spec.files.single().compressedName}.part")
            .writeBytes(byteArrayOf(1))

        assertEquals(
            ModelDownloadState.READY,
            resolveBergamotModelDownloadState(root, spec, hasPartial = true),
        )

        File(directory, spec.files.single().outputName).writeBytes(byteArrayOf(6, 2, 0, 2))

        assertEquals(
            ModelDownloadState.PARTIAL,
            resolveBergamotModelDownloadState(root, spec, hasPartial = true),
        )
    }

    @Test
    fun completeValidDownloadPartIsReusedWithoutEofRange() {
        val part = temporaryFolder.newFile("model.bin.gz.part")
        val content = byteArrayOf(1, 3, 3, 7)
        part.writeBytes(content)

        val decision = BergamotPartialDownloadPlanner.prepare(
            destination = part,
            expectedSize = content.size.toLong(),
            expectedSha256 = sha256(content),
        )

        assertTrue(decision.reuseComplete)
        assertEquals(content.size.toLong(), decision.existingBytes)
        assertEquals(null, decision.rangeStart)
        assertTrue(part.isFile)
        assertTrue(part.readBytes().contentEquals(content))
    }

    @Test
    fun completeCorruptDownloadPartIsDeletedBeforeRestartingAtZero() {
        val part = temporaryFolder.newFile("model.bin.gz.part")
        val expected = byteArrayOf(1, 3, 3, 7)
        part.writeBytes(byteArrayOf(7, 3, 3, 1))

        val decision = BergamotPartialDownloadPlanner.prepare(
            destination = part,
            expectedSize = expected.size.toLong(),
            expectedSha256 = sha256(expected),
        )

        assertFalse(decision.reuseComplete)
        assertEquals(0L, decision.existingBytes)
        assertEquals(null, decision.rangeStart)
        assertFalse(part.exists())
    }

    @Test
    fun truncatedDownloadPartResumesAtItsCurrentLength() {
        val part = temporaryFolder.newFile("model.bin.gz.part")
        part.writeBytes(byteArrayOf(1, 3))

        val decision = BergamotPartialDownloadPlanner.prepare(
            destination = part,
            expectedSize = 4L,
            expectedSha256 = sha256(byteArrayOf(1, 3, 3, 7)),
        )

        assertFalse(decision.reuseComplete)
        assertEquals(2L, decision.existingBytes)
        assertEquals(2L, decision.rangeStart)
        assertTrue(part.isFile)
    }

    @Test
    fun blockingProtocolReadTimesOutWithinBound() {
        val blockingReader = BlockingReader()
        val lineReader = BergamotBoundedLineReader(
            reader = BufferedReader(blockingReader),
            threadName = "bergamot-timeout-test",
        )
        val startedAt = System.nanoTime()

        val error = try {
            runCatching {
                lineReader.readLine(
                    timeoutMillis = 100L,
                    operation = "test response",
                )
            }.exceptionOrNull()
        } finally {
            lineReader.close()
            blockingReader.close()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("timed out"))
        assertTrue("read took ${elapsedMillis}ms", elapsedMillis < 2_000L)
    }

    @Test
    fun stderrDrainThreadContainsInterruptedCloseWithoutUncaughtException() {
        val expected = InterruptedIOException("read interrupted by close() on another thread")
        val stream = object : InputStream() {
            override fun read(): Int = throw expected
        }
        val lines = mutableListOf<String>()
        val failure = AtomicReference<Exception?>()
        val uncaught = AtomicReference<Throwable?>()
        val thread = Thread {
            failure.set(consumeBergamotStderr(stream, lines::add))
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                uncaught.set(error)
            }
        }

        thread.start()
        thread.join(1_000L)

        assertFalse(thread.isAlive)
        assertTrue(failure.get() === expected)
        assertEquals(null, uncaught.get())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun stderrDrainForwardsLinesAndTreatsEofAsSuccess() {
        val lines = mutableListOf<String>()

        val failure = consumeBergamotStderr(
            ByteArrayInputStream("first\nsecond\n".toByteArray()),
            lines::add,
        )

        assertEquals(null, failure)
        assertEquals(listOf("first", "second"), lines)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private class BlockingReader : Reader() {
        private val released = CountDownLatch(1)

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            released.await()
            return -1
        }

        override fun close() {
            released.countDown()
        }
    }
}
