package com.screentranslation.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelPreparationDescriptorTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun `task identity is stable across coordinate ordering`() {
        val first = descriptor(
            modelIds = listOf("model-b", "model-a"),
            revisions = listOf("r2", "r1"),
            hashes = listOf(hashB, hashA),
        )
        val reordered = descriptor(
            modelIds = listOf("model-a", "model-b"),
            revisions = listOf("r1", "r2"),
            hashes = listOf(hashA.uppercase(), hashB),
        )
        assertEquals(first.taskId, reordered.taskId)
        assertEquals(64, first.taskId.length)
    }

    @Test
    fun `task identity changes with artifact or size coordinates`() {
        val baseline = descriptor()
        assertNotEquals(baseline.taskId, descriptor(hashes = listOf(hashB)).taskId)
        assertNotEquals(baseline.taskId, descriptor(downloadBytes = 2L).taskId)
        assertNotEquals(baseline.taskId, descriptor(edition = "full").taskId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid artifact hash is rejected`() {
        descriptor(hashes = listOf("not-a-sha256"))
    }

    private fun descriptor(
        edition: String = "lite",
        modelIds: List<String> = listOf("model-a"),
        revisions: List<String> = listOf("r1"),
        hashes: List<String> = listOf(hashA),
        downloadBytes: Long = 1L,
    ) = ModelPreparationDescriptor(
        edition = edition,
        modelIds = modelIds,
        revisions = revisions,
        expectedSha256 = hashes,
        downloadBytes = downloadBytes,
        installedBytes = 3L,
    )
}
