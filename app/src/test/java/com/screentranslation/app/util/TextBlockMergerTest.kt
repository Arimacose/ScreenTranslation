package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockMergerTest {

    @Test
    fun `keeps a single block untouched`() {
        assertEquals(
            listOf("Select the area you want to translate."),
            TextBlockMerger.merge(listOf("Select the area you want to translate.")),
        )
    }

    @Test
    fun `keeps an empty list untouched`() {
        assertEquals(emptyList<String>(), TextBlockMerger.merge(emptyList()))
    }

    /**
     * The case observed on device: the tail of a wrapped sentence arrived as its
     * own block and was translated in isolation as 「可用。」.
     */
    @Test
    fun `rejoins a sentence split across blocks`() {
        val blocks = listOf(
            "The translation engine runs entirely on your device, which means the " +
                "text captured from the screen never leaves the phone and the model " +
                "keeps working even when there is no network connection",
            "available at all.",
        )

        val merged = TextBlockMerger.merge(blocks)

        assertEquals(1, merged.size)
        assertEquals(
            "The translation engine runs entirely on your device, which means the " +
                "text captured from the screen never leaves the phone and the model " +
                "keeps working even when there is no network connection available at all.",
            merged.single(),
        )
    }

    /**
     * The regression that a naive "no full stop means continuation" rule would
     * cause: menu labels carry no punctuation, and fusing them recreates exactly
     * the cross-contamination that per-block translation exists to prevent.
     */
    @Test
    fun `keeps unpunctuated menu labels separate`() {
        val blocks = listOf(
            "Display and brightness",
            "Battery and performance",
            "Storage",
            "Notifications and control center",
            "Privacy protection",
        )

        assertEquals(blocks, TextBlockMerger.merge(blocks))
    }

    @Test
    fun `does not merge across a finished sentence`() {
        val blocks = listOf(
            "Rotate the screen to confirm that the selection is discarded.",
            "lock the phone to confirm that recognition pauses.",
        )

        assertEquals(blocks, TextBlockMerger.merge(blocks))
    }

    @Test
    fun `merges a block that opens on continuation punctuation`() {
        val merged = TextBlockMerger.merge(listOf("Some manufacturers reclaim services", ", so sessions break"))

        assertEquals(listOf("Some manufacturers reclaim services , so sessions break"), merged)
    }

    @Test
    fun `treats a heading as separate from its body`() {
        val blocks = listOf(
            "Battery optimization",
            "Some manufacturers reclaim foreground services aggressively.",
        )

        assertEquals(blocks, TextBlockMerger.merge(blocks))
    }

    @Test
    fun `stops merging once the combined text gets long`() {
        val head = "word ".repeat(150).trim()
        val blocks = listOf(head, "and more text")

        val merged = TextBlockMerger.merge(blocks)

        assertEquals("an over-long chain must not be rebuilt", 2, merged.size)
    }

    @Test
    fun `drops blank blocks without breaking the chain`() {
        val merged = TextBlockMerger.merge(listOf("there is no network connection", "   ", "available at all."))

        assertEquals(listOf("there is no network connection available at all."), merged)
    }

    @Test
    fun `leaves cjk blocks alone because they carry no case signal`() {
        val blocks = listOf("显示与亮度", "电池与性能", "存储空间")

        assertEquals(blocks, TextBlockMerger.merge(blocks))
    }

    @Test
    fun `trims surrounding whitespace on every block`() {
        val merged = TextBlockMerger.merge(listOf("  Storage  ", "  Notifications  "))

        assertEquals(listOf("Storage", "Notifications"), merged)
    }
}
