package com.screentranslation.app.util

/**
 * Rejoins OCR text blocks that are really one sentence split across a layout
 * boundary, before each block is translated separately.
 *
 * OCR can split a wrapped paragraph into several text blocks. Translating the
 * tail of a sentence on its own produces a disconnected
 * fragment: "...no network connection" / "available at all." came back as a
 * standalone 「可用。」 on device.
 *
 * The naive rule -- merge whenever the previous block lacks terminal
 * punctuation -- is worse than doing nothing, because unpunctuated blocks are
 * also exactly what a menu looks like ("Storage", "Notifications"), and fusing
 * those recreates the context contamination that per-block translation exists
 * to avoid. So merging requires positive evidence of continuation rather than
 * merely the absence of a full stop.
 *
 * Scripts without letter case (Chinese, Japanese, Korean) never satisfy the
 * lowercase test, so they retain one-block-per-unit behaviour.
 */
object TextBlockMerger {

    fun merge(blocks: List<String>): List<String> {
        if (blocks.size < 2) return blocks

        val merged = mutableListOf<String>()
        var current = StringBuilder(blocks.first().trim())

        for (index in 1 until blocks.size) {
            val next = blocks[index].trim()
            if (next.isEmpty()) continue

            if (continuesSentence(current, next)) {
                current.append(' ').append(next)
            } else {
                merged += current.toString()
                current = StringBuilder(next)
            }
        }
        merged += current.toString()
        return merged
    }

    private fun continuesSentence(current: CharSequence, next: String): Boolean {
        val previous = current.lastOrNull { !it.isWhitespace() } ?: return false

        // A finished sentence never continues into the next block.
        if (previous in SENTENCE_TERMINATORS) return false

        // Keep a bound on the result: an over-eager chain would rebuild the
        // single long string that per-block translation is meant to break up.
        if (current.length + next.length > MAX_MERGED_LENGTH) return false

        val first = next.firstOrNull { !it.isWhitespace() } ?: return false

        // Positive evidence only. A lowercase opener, or a block that opens on
        // punctuation that cannot start a sentence, means the layout wrapped
        // mid-sentence rather than starting a new item.
        return first.isLowerCase() || first in CONTINUATION_OPENERS
    }

    private const val MAX_MERGED_LENGTH = 600
    private val SENTENCE_TERMINATORS = charArrayOf(
        '.', '!', '?', ':', ';',
        '。', '！', '？', '：', '；', '…',
    )
    private val CONTINUATION_OPENERS = charArrayOf(',', ';', ')', ']', '，', '、', '）')
}
