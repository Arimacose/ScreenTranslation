package com.screentranslation.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
