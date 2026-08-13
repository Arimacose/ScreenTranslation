package com.screentranslation.app.model.preparation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPreparationStateTest {
    @Test
    fun `transfer estimate reports measured speed and ceiling eta`() {
        val estimate = estimateTransfer(
            previousBytes = 1_000L,
            currentBytes = 4_000L,
            elapsedMillis = 1_500L,
            totalBytes = 9_001L,
        )
        assertEquals(2_000L, estimate.bytesPerSecond)
        assertEquals(3L, estimate.etaSeconds)
    }

    @Test
    fun `transfer estimate suppresses stale or unknown observations`() {
        assertEquals(0L, estimateTransfer(10L, 10L, 1_000L, 100L).bytesPerSecond)
        assertNull(estimateTransfer(10L, 10L, 1_000L, 100L).etaSeconds)
        assertNull(estimateTransfer(0L, 10L, 1_000L, 0L).etaSeconds)
    }

    @Test
    fun `storage preflight includes peak install usage margin and fixed headroom`() {
        val required = requiredPreparationBytes(
            downloadBytes = 100L,
            installedBytes = 300L,
            existingBytes = 100L,
        )
        assertEquals(330L + MIN_STORAGE_HEADROOM_BYTES, required)
        assertEquals(0L, requiredPreparationBytes(0L, 0L, 999L))
    }
}
