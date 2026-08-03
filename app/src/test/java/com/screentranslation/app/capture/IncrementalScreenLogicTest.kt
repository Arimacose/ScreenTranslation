package com.screentranslation.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalScreenLogicTest {
    @Test
    fun `block becomes stable on the second identical observation and keeps its id`() {
        val tracker = IncrementalBlockTracker()
        val block = block("Settings", 0.1f, 0.2f, 0.5f, 0.3f)

        val first = tracker.update(listOf(block)).single()
        val second = tracker.update(listOf(block.copy(bounds = bounds(0.11f, 0.2f, 0.51f, 0.3f))))
            .single()

        assertFalse(first.isStable)
        assertTrue(second.isStable)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `changed text in the same box must stabilize again`() {
        val tracker = IncrementalBlockTracker()
        val initial = block("Old subtitle", 0.1f, 0.7f, 0.8f, 0.8f)
        val id = tracker.update(listOf(initial)).single().id
        tracker.update(listOf(initial))

        val changed = initial.copy(text = "New subtitle")
        val firstChanged = tracker.update(listOf(changed)).single()
        val stableChanged = tracker.update(listOf(changed)).single()

        assertEquals(id, firstChanged.id)
        assertFalse(firstChanged.isStable)
        assertTrue(stableChanged.isStable)
    }

    @Test
    fun `tile differ reports natural and forced tiles separately`() {
        val differ = TileSignatureDiffer()
        val baseline = listOf(IntArray(100) { 120 }, IntArray(100) { 80 })
        assertEquals(setOf(0, 1), differ.compare(baseline).natural)

        val changed = baseline.map { it.clone() }
        repeat(10) { changed[1][it] = 10 }
        val result = differ.compare(changed, forced = setOf(0))

        assertEquals(setOf(1), result.natural)
        assertEquals(setOf(0, 1), result.all)
    }

    @Test
    fun `adaptive sampling backs off and immediately returns to active rate`() {
        val interval = AdaptiveFrameInterval(
            activeIntervalMs = 250L,
            maximumIntervalMs = 2_000L,
            unchangedFramesPerStep = 2,
        )

        assertEquals(250L, interval.recordChanged(false))
        assertEquals(500L, interval.recordChanged(false))
        repeat(6) { interval.recordChanged(false) }
        assertEquals(2_000L, interval.currentIntervalMs)
        assertEquals(250L, interval.recordChanged(true))
    }

    @Test
    fun `tile geometry maps back to full screen normalized coordinates`() {
        val mapped = mapTileRegionToScreen(
            tileCrop = PixelTile(0, 100, 200, 500, 600),
            frameWidth = 1_000,
            frameHeight = 1_000,
            left = 0.25f,
            top = 0.25f,
            right = 0.75f,
            bottom = 0.75f,
        )

        assertEquals(0.2f, mapped.left, 0.0001f)
        assertEquals(0.3f, mapped.top, 0.0001f)
        assertEquals(0.4f, mapped.right, 0.0001f)
        assertEquals(0.5f, mapped.bottom, 0.0001f)
    }

    @Test
    fun `normalized points map to the same three by six tile order`() {
        assertEquals(0, ScreenTileGrid.indexForNormalizedPoint(0f, 0f))
        assertEquals(4, ScreenTileGrid.indexForNormalizedPoint(0.5f, 0.2f))
        assertEquals(17, ScreenTileGrid.indexForNormalizedPoint(1f, 1f))
    }

    @Test
    fun `an unstable block keeps its tile in the next verification pass`() {
        val unstable = TrackedScreenTextBlock(
            id = 1,
            text = "late OCR result",
            bounds = bounds(0.4f, 0.35f, 0.6f, 0.45f),
            confidence = 0.9f,
            isStable = false,
        )
        val stable = unstable.copy(id = 2, bounds = bounds(0.1f, 0.8f, 0.3f, 0.9f), isStable = true)

        assertEquals(
            setOf(0, ScreenTileGrid.indexForNormalizedPoint(0.5f, 0.4f)),
            verificationTileIndices(setOf(0), listOf(unstable, stable)),
        )
    }

    @Test
    fun `translation label is placed directly above its source box`() {
        val placement = resolveTranslationPlacement(
            bounds = bounds(0.1f, 0.5f, 0.4f, 0.6f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 96,
            maximumWidth = 720,
            gap = 3,
        )

        assertEquals(100, placement.left)
        assertEquals(417, placement.top)
        assertEquals(300, placement.width)
    }

    @Test
    fun `translation label falls below only when top space is insufficient`() {
        val placement = resolveTranslationPlacement(
            bounds = bounds(0.1f, 0.02f, 0.4f, 0.08f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 96,
            maximumWidth = 720,
            gap = 3,
        )

        assertEquals(83, placement.top)
    }

    private fun block(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ScreenTextBlock(text, bounds(left, top, right, bottom), 0.9f)

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBounds(left, top, right, bottom)
}
