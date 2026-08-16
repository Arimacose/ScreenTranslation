package com.screentranslation.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val changedTile = ScreenTileGrid.indexForNormalizedPoint(
            changed.bounds.centerX,
            changed.bounds.centerY,
        )
        val firstChanged = tracker.update(
            rawBlocks = listOf(changed),
            invalidatedTiles = setOf(changedTile),
        ).single()
        val stableChanged = tracker.update(listOf(changed)).single()

        assertEquals(id, firstChanged.id)
        assertEquals("New subtitle", firstChanged.text)
        assertFalse(firstChanged.isStable)
        assertTrue(stableChanged.isStable)
    }

    @Test
    fun `repeated noisy text cannot replace a committed line without owner tile change`() {
        val tracker = IncrementalBlockTracker()
        val initial = block("Contact demo@example.com", 0.1f, 0.7f, 0.8f, 0.8f)
        tracker.update(listOf(initial))
        val stable = tracker.update(listOf(initial)).single()
        val noisy = initial.copy(text = "Contact demo@example.corn")

        val firstNoise = tracker.update(listOf(noisy)).single()
        val repeatedNoise = tracker.update(listOf(noisy)).single()

        assertEquals(stable.id, firstNoise.id)
        assertEquals(initial.text, firstNoise.text)
        assertEquals(initial.bounds, firstNoise.bounds)
        assertTrue(firstNoise.isStable)
        assertEquals(initial.text, repeatedNoise.text)
        assertEquals(initial.bounds, repeatedNoise.bounds)
        assertTrue(repeatedNoise.isStable)
    }

    @Test
    fun `dirty neighboring tile crop cannot replace committed owner tile text`() {
        val tracker = IncrementalBlockTracker()
        val complete = block(
            "The quiet river carried the last light of evening.",
            0.06f,
            0.14f,
            0.84f,
            0.18f,
        )
        tracker.update(listOf(complete))
        val stable = tracker.update(listOf(complete)).single()
        val neighboringCropNoise = block(
            "quiet river ue last lgnt oi evening",
            0.08f,
            0.155f,
            0.62f,
            0.195f,
        )
        val neighboringTile = ScreenTileGrid.indexForNormalizedPoint(
            neighboringCropNoise.bounds.centerX,
            neighboringCropNoise.bounds.centerY,
        )
        val ownerTile = ScreenTileGrid.indexForNormalizedPoint(
            complete.bounds.centerX,
            complete.bounds.centerY,
        )
        assertFalse(ownerTile == neighboringTile)

        repeat(4) {
            val tracked = tracker.update(
                rawBlocks = listOf(neighboringCropNoise),
                invalidatedTiles = setOf(neighboringTile),
            ).single()
            assertEquals(stable.id, tracked.id)
            assertEquals(complete.text, tracked.text)
            assertEquals(complete.bounds, tracked.bounds)
            assertTrue(tracked.isStable)
        }
    }

    @Test
    fun `repeated narrow fragments never replace a committed complete sentence`() {
        val tracker = IncrementalBlockTracker()
        val complete = block(
            "A careful reader notices what haste would miss.",
            0.06f,
            0.36f,
            0.84f,
            0.43f,
        )
        tracker.update(listOf(complete))
        val stable = tracker.update(listOf(complete)).single()
        val clipped = block(
            "what haste would",
            0.38f,
            0.36f,
            0.64f,
            0.43f,
        )

        repeat(4) {
            val tracked = tracker.update(listOf(clipped)).single()
            assertEquals(stable.id, tracked.id)
            assertEquals(complete.text, tracked.text)
            assertEquals(complete.bounds, tracked.bounds)
            assertTrue(tracked.isStable)
        }
    }

    @Test
    fun `same committed text keeps its full geometry during forced verification`() {
        val tracker = IncrementalBlockTracker()
        val complete = block(
            "Contact demo@example.com before 18:30.",
            0.06f,
            0.70f,
            0.84f,
            0.76f,
        )
        tracker.update(listOf(complete))
        val stable = tracker.update(listOf(complete)).single()
        val implausiblyNarrow = complete.copy(bounds = bounds(0.34f, 0.70f, 0.66f, 0.76f))

        val verified = tracker.update(listOf(implausiblyNarrow)).single()

        assertEquals(stable.id, verified.id)
        assertEquals(complete.text, verified.text)
        assertEquals(complete.bounds, verified.bounds)
        assertTrue(verified.isStable)
    }

    @Test
    fun `stable block survives two missed OCR observations outside dirty tiles`() {
        val tracker = IncrementalBlockTracker(maximumMissedObservations = 2)
        val initial = block("Version 2.4.1", 0.1f, 0.4f, 0.8f, 0.5f)
        val id = tracker.update(listOf(initial)).single().id
        tracker.update(listOf(initial))

        assertEquals(id, tracker.update(emptyList()).single().id)
        assertEquals(id, tracker.update(emptyList()).single().id)
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    @Test
    fun `missing block in a dirty tile is removed immediately`() {
        val tracker = IncrementalBlockTracker()
        val initial = block("Old screen", 0.1f, 0.4f, 0.8f, 0.5f)
        tracker.update(listOf(initial))
        tracker.update(listOf(initial))
        val tile = ScreenTileGrid.indexForNormalizedPoint(
            initial.bounds.centerX,
            initial.bounds.centerY,
        )

        assertTrue(tracker.update(emptyList(), invalidatedTiles = setOf(tile)).isEmpty())
    }

    @Test
    fun `overlapping tile prefix is removed in favor of the complete OCR line`() {
        val tracker = IncrementalBlockTracker()
        val complete = block(
            "Contact demo@example.com before 18:30.",
            0.18f,
            0.70f,
            0.82f,
            0.76f,
        )
        val clippedPrefix = block(
            "Contact demo@e",
            0.06f,
            0.70f,
            0.36f,
            0.76f,
        ).copy(confidence = 0.98f)

        val tracked = tracker.update(listOf(clippedPrefix, complete))

        assertEquals(1, tracked.size)
        assertEquals(complete.text, tracked.single().text)
    }

    @Test
    fun `separate controls with contained text remain distinct when geometry does not overlap`() {
        val tracker = IncrementalBlockTracker()
        val save = block("Save item", 0.05f, 0.40f, 0.22f, 0.47f)
        val saveAs = block("Save item as", 0.30f, 0.40f, 0.52f, 0.47f)

        val tracked = tracker.update(listOf(save, saveAs))

        assertEquals(2, tracked.size)
    }

    @Test
    fun `complementary text fragments across a tile boundary form one block`() {
        val tracker = IncrementalBlockTracker()
        val left = block(
            "Every difficult problem",
            0.06f,
            0.40f,
            0.35f,
            0.47f,
        )
        val right = block(
            "becomes clearer with patience.",
            0.32f,
            0.40f,
            0.84f,
            0.47f,
        )

        val tracked = tracker.update(listOf(left, right))

        assertEquals(1, tracked.size)
        assertEquals(
            "Every difficult problem becomes clearer with patience.",
            tracked.single().text,
        )
    }

    @Test
    fun `email split at a tile boundary rejoins without whitespace`() {
        val tracker = IncrementalBlockTracker()
        val left = block(
            "Contact demo@e",
            0.06f,
            0.70f,
            0.35f,
            0.76f,
        )
        val right = block(
            "xample.com before 18:30.",
            0.32f,
            0.70f,
            0.76f,
            0.76f,
        )

        val tracked = tracker.update(listOf(left, right))

        assertEquals(1, tracked.size)
        assertEquals("Contact demo@example.com before 18:30.", tracked.single().text)
    }

    @Test
    fun `same-row controls away from a tile boundary are not merged`() {
        val tracker = IncrementalBlockTracker()
        val cancel = block("Cancel", 0.06f, 0.55f, 0.18f, 0.62f)
        val confirm = block("Confirm", 0.22f, 0.55f, 0.31f, 0.62f)

        val tracked = tracker.update(listOf(cancel, confirm))

        assertEquals(2, tracked.size)
    }

    @Test
    fun `tile differ reports natural and forced tiles separately`() {
        val differ = TileSignatureDiffer()
        val baseline = listOf(ByteArray(100) { 120 }, ByteArray(100) { 80 })
        assertEquals(setOf(0, 1), differ.compare(baseline).natural)

        val changed = baseline.map { it.clone() }
        repeat(10) { changed[1][it] = 10.toByte() }
        val result = differ.compare(changed, forced = setOf(0))

        assertEquals(setOf(1), result.natural)
        assertEquals(setOf(0, 1), result.all)
    }

    @Test
    fun `overlay mask changes suppress only touched tiles and keep forced verification`() {
        val differ = TileSignatureDiffer()
        val baseline = listOf(ByteArray(100) { 120 }, ByteArray(100) { 80 })
        differ.compare(baseline)
        assertTrue(differ.hasBaseline)

        val masked = baseline.map { it.clone() }
        repeat(20) { masked[0][it] = 255.toByte() }
        val rebased = differ.compare(
            current = masked,
            forced = setOf(1),
            suppressedNaturalTiles = setOf(0),
        )

        assertEquals(emptySet<Int>(), rebased.natural)
        assertEquals(setOf(1), rebased.all)
        assertEquals(emptySet<Int>(), differ.compare(masked).all)
    }

    @Test
    fun `real changes outside a changed overlay mask remain visible`() {
        val differ = TileSignatureDiffer()
        val baseline = listOf(ByteArray(100) { 120 }, ByteArray(100) { 80 })
        differ.compare(baseline)
        val changed = baseline.map { it.clone() }
        repeat(20) {
            changed[0][it] = 255.toByte()
            changed[1][it] = 10.toByte()
        }

        val result = differ.compare(changed, suppressedNaturalTiles = setOf(0))

        assertEquals(setOf(1), result.natural)
        assertEquals(setOf(1), result.all)
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
    fun `changed tiles hide stale translated blocks immediately`() {
        val top = trackedBlock(id = 1, top = 0.1f, bottom = 0.2f)
        val bottom = trackedBlock(id = 2, top = 0.8f, bottom = 0.9f)
        val changedTile = ScreenTileGrid.indexForNormalizedPoint(
            top.bounds.centerX,
            top.bounds.centerY,
        )

        assertEquals(listOf(bottom), blocksOutsideTiles(listOf(top, bottom), setOf(changedTile)))
        assertEquals(listOf(top, bottom), blocksOutsideTiles(listOf(top, bottom), emptySet()))
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

    @Test
    fun `top label moves below the control bar and remains inside the safe area`() {
        val placement = resolveNonOverlappingTranslationPlacement(
            bounds = bounds(0.1f, 0.02f, 0.4f, 0.08f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 96,
            maximumWidth = 720,
            gap = 3,
            minimumTop = 100,
            maximumBottom = 950,
            occupied = listOf(TranslationObstacle(0, 100, 900, 200)),
        )

        assertEquals(203, placement?.top)
    }

    @Test
    fun `later translation label searches upward instead of overlapping`() {
        val placement = resolveNonOverlappingTranslationPlacement(
            bounds = bounds(0.1f, 0.52f, 0.4f, 0.60f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 96,
            maximumWidth = 720,
            gap = 3,
            occupied = listOf(TranslationObstacle(100, 417, 400, 497)),
        )

        assertEquals(334, placement?.top)
    }

    @Test
    fun `label is omitted when every safe slot is occupied`() {
        val placement = resolveNonOverlappingTranslationPlacement(
            bounds = bounds(0.1f, 0.5f, 0.4f, 0.6f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 96,
            maximumWidth = 720,
            gap = 3,
            minimumTop = 100,
            maximumBottom = 900,
            occupied = listOf(TranslationObstacle(0, 100, 1_000, 900)),
        )

        assertNull(placement)
    }

    @Test
    fun `horizontal placement is used after aligned above and below slots are exhausted`() {
        val placement = resolveNonOverlappingTranslationPlacement(
            bounds = bounds(0.40f, 0.40f, 0.50f, 0.50f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 100,
            maximumWidth = 200,
            gap = 3,
            occupied = listOf(TranslationObstacle(400, 0, 500, 1_000)),
        )

        assertEquals(TranslationPlacementStrategy.HORIZONTAL_START, placement?.strategy)
        assertEquals(297, placement?.left)
    }

    @Test
    fun `stack placement finds a safe surface after source adjacent strategies collide`() {
        val placement = resolveNonOverlappingTranslationPlacement(
            bounds = bounds(0.40f, 0.40f, 0.50f, 0.50f),
            screenWidth = 1_000,
            screenHeight = 1_000,
            labelHeight = 80,
            minimumWidth = 100,
            maximumWidth = 200,
            gap = 3,
            occupied = listOf(
                TranslationObstacle(297, 0, 600, 1_000),
            ),
        )

        assertEquals(TranslationPlacementStrategy.STACK, placement?.strategy)
        assertEquals(0, placement?.left)
        assertEquals(0, placement?.top)
    }

    @Test
    fun `adjacent overlay merge retains every block identity and complete pair`() {
        val merged = mergeAdjacentOverlayBlocks(
            listOf(
                overlayBlock(1, "Hello", "你好", 0.10f, 0.20f, 0.28f, 0.25f),
                overlayBlock(2, "world", "世界", 0.29f, 0.20f, 0.46f, 0.25f),
                overlayBlock(3, "Separate", "分开", 0.10f, 0.50f, 0.35f, 0.56f),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf(1L, 2L), merged.first().ids)
        assertEquals(listOf("Hello", "world"), merged.first().originalTexts)
        assertEquals("你好\n世界", merged.first().displayTranslation)
        assertEquals(setOf(1L, 2L, 3L), merged.flatMap { it.ids }.toSet())
    }

    @Test
    fun `twenty dense blocks remain discoverable after display merging`() {
        val dense = (0 until 20).map { index ->
            val row = index / 4
            val column = index % 4
            overlayBlock(
                id = index.toLong() + 1,
                original = "source-$index",
                translation = "translation-$index",
                left = 0.04f + column * 0.23f,
                top = 0.12f + row * 0.13f,
                right = 0.23f + column * 0.23f,
                bottom = 0.18f + row * 0.13f,
            )
        }

        val rendered = mergeAdjacentOverlayBlocks(dense)

        assertEquals((1L..20L).toSet(), rendered.flatMap { it.ids }.toSet())
        assertEquals(
            dense.flatMap { it.originalTexts },
            rendered.flatMap { it.originalTexts },
        )
        assertEquals(
            dense.flatMap { it.translatedTexts },
            rendered.flatMap { it.translatedTexts },
        )
    }

    private fun overlayBlock(
        id: Long,
        original: String,
        translation: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = OverlayTranslationBlock(
        ids = listOf(id),
        originalTexts = listOf(original),
        translatedTexts = listOf(translation),
        bounds = bounds(left, top, right, bottom),
    )

    private fun block(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ScreenTextBlock(text, bounds(left, top, right, bottom), 0.9f)

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBounds(left, top, right, bottom)

    private fun trackedBlock(id: Long, top: Float, bottom: Float) = TrackedScreenTextBlock(
        id = id,
        text = "Block $id",
        bounds = bounds(0.1f, top, 0.4f, bottom),
        confidence = 0.9f,
        isStable = true,
    )
}
