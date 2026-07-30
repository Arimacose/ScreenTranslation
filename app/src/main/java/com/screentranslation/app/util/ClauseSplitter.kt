package com.screentranslation.app.util

/**
 * Breaks a long sentence into clauses so each is translated on its own.
 *
 * On-device NMT degrades sharply on long multi-clause input. Measured on
 * device, ML Kit turned
 *
 *   "...the text never leaves the phone and the model keeps working even when
 *    there is no network connection"
 *
 * into a translation that **inverted the negation and dropped a whole clause**.
 * The same model handles each clause correctly in isolation.
 *
 * This trades fluency for correctness: several short translated sentences read
 * choppier than one flowing sentence, but a choppy translation that preserves
 * the meaning beats a smooth one that reverses it.
 *
 * Splitting is deliberately conservative:
 * - short input is left alone, because it was never the problem
 * - only connectors that introduce a syntactically complete clause are used, so
 *   each piece can stand on its own as translator input
 * - a split that would leave a stub on either side is rejected
 */
object ClauseSplitter {

    /**
     * Translation units plus enough layout information to put the results back
     * together: clauses from one OCR block are joined inline, while independent
     * OCR blocks remain on separate lines.
     */
    class Plan internal constructor(
        clausesByBlock: List<List<String>>,
    ) {
        private val clauseCounts = clausesByBlock.map { it.size }
        val parts: List<String> = clausesByBlock.flatten()

        fun reassemble(translatedParts: List<String>): String {
            require(translatedParts.size == parts.size) {
                "Expected ${parts.size} translated clauses, got ${translatedParts.size}"
            }

            var offset = 0
            return clauseCounts.joinToString("\n") { count ->
                translatedParts
                    .subList(offset, offset + count)
                    .joinToString(" ")
                    .also { offset += count }
            }
        }
    }

    fun plan(blocks: List<String>): Plan = Plan(
        blocks.map(::split),
    )

    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_UNSPLIT_LENGTH) return listOf(trimmed)

        val pieces = mutableListOf<String>()
        var rest = trimmed

        while (rest.length > MAX_UNSPLIT_LENGTH) {
            val cut = findCut(rest) ?: break
            pieces += rest.substring(0, cut.first).trim()
            rest = rest.substring(cut.second).trim()
        }

        // Once a long CJK paragraph has needed splitting, keep the remaining
        // complete sentences separate even when their combined tail is shorter
        // than MAX_UNSPLIT_LENGTH. This avoids feeding two independent Japanese
        // sentences back to the translator as one unit, while the early return
        // above still leaves genuinely short input untouched.
        if (pieces.isNotEmpty()) {
            while (true) {
                val cut = findCjkSentenceCut(rest) ?: break
                pieces += rest.substring(0, cut.first).trim()
                rest = rest.substring(cut.second).trim()
            }
        }
        if (rest.isNotEmpty()) pieces += rest

        // Nothing usable was found; hand back the original rather than a
        // single-element list built from a half-applied split.
        return if (pieces.size < 2) listOf(trimmed) else pieces
    }

    /**
     * Returns `end of left piece` to `start of right piece`, or null when no
     * connector leaves both sides substantial enough to translate.
     */
    private fun findCut(text: String): Pair<Int, Int>? {
        var best = findCjkSentenceCut(text)

        for (marker in MARKERS) {
            val at = text.indexOf(marker, startIndex = MIN_CLAUSE_LENGTH, ignoreCase = true)
            if (at < 0) continue

            // Drop the comma and whitespace, keep the connector itself with the
            // right-hand clause: "and the model keeps working" reads as a clause,
            // a bare "the model keeps working" loses the link to what precedes it.
            val skip = if (marker.startsWith(",")) 2 else 1
            val rightStart = at + skip
            if (text.length - rightStart < MIN_CLAUSE_LENGTH) continue

            if (best == null || at < best.first) best = at to rightStart
        }
        return best
    }

    private fun findCjkSentenceCut(text: String): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (terminator in CJK_SENTENCE_TERMINATORS) {
            val at = text.indexOf(
                terminator,
                startIndex = MIN_CLAUSE_LENGTH,
            )
            if (at < 0) continue
            val boundary = at + terminator.length
            if (text.length - boundary < MIN_CLAUSE_LENGTH) continue
            if (best == null || boundary < best.first) {
                best = boundary to boundary
            }
        }
        return best
    }

    /**
     * Ordered longest-first so a specific connector is preferred over the
     * generic one nested inside it.
     */
    private val MARKERS = listOf(
        "; ",
        ", in which case ",
        ", which means ",
        ", although ", " although ",
        ", because ", " because ",
        ", whereas ", " whereas ",
        ", unless ", " unless ",
        ", which ", ", who ", ", where ", ", while ",
        ", and ", ", but ", ", so ", ", or ", ", yet ",
        " and ", " but ", " so ", " yet ",
    )

    /** Preserve Japanese/CJK sentence punctuation on the left-hand unit. */
    private val CJK_SENTENCE_TERMINATORS = listOf("。", "！", "？")

    /** Below this the model was never in trouble, so leave the text intact. */
    private const val MAX_UNSPLIT_LENGTH = 90

    /** A fragment shorter than this carries too little context to translate. */
    private const val MIN_CLAUSE_LENGTH = 20
}
