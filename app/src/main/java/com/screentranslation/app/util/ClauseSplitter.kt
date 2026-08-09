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

        // Sentence punctuation inside URLs, decimals, dates, and versions must
        // never become a semantic boundary. Split the encoded representation,
        // then restore exact values independently in every resulting clause.
        val protected = ProtectedTextCodec.protect(trimmed)
        if (trimmed.length > MAX_INPUT_LENGTH) {
            return chunkForBackend(protected.encoded).map(protected::restore)
        }

        val encoded = protected.encoded
        val candidates = findCuts(encoded)
        val pieces = mutableListOf<String>()
        var cursor = 0
        var candidateIndex = 0

        while (encoded.length - cursor > MAX_UNSPLIT_LENGTH) {
            val found = nextCut(
                textLength = encoded.length,
                candidates = candidates,
                startIndex = candidateIndex,
                cursor = cursor,
                sentenceOnly = false,
            ) ?: break
            val cut = found.first
            candidateIndex = found.second
            pieces += encoded.substring(cursor, cut.leftEnd).trim()
            cursor = cut.rightStart
            while (cursor < encoded.length && encoded[cursor].isWhitespace()) cursor += 1
        }

        // Once a long CJK paragraph has needed splitting, keep the remaining
        // complete sentences separate even when their combined tail is shorter
        // than MAX_UNSPLIT_LENGTH. This avoids feeding two independent Japanese
        // sentences back to the translator as one unit, while the early return
        // above still leaves genuinely short input untouched.
        if (pieces.isNotEmpty()) {
            while (true) {
                val found = nextCut(
                    textLength = encoded.length,
                    candidates = candidates,
                    startIndex = candidateIndex,
                    cursor = cursor,
                    sentenceOnly = true,
                ) ?: break
                val cut = found.first
                candidateIndex = found.second
                pieces += encoded.substring(cursor, cut.leftEnd).trim()
                cursor = cut.rightStart
                while (cursor < encoded.length && encoded[cursor].isWhitespace()) cursor += 1
            }
        }
        if (cursor < encoded.length) pieces += encoded.substring(cursor).trim()

        val boundedPieces = if (pieces.size < 2) {
            chunkForBackend(encoded)
        } else {
            pieces.flatMap(::chunkForBackend)
        }
        return boundedPieces.map(protected::restore)
    }

    /**
     * Caps every backend request even for malformed or extremely long OCR.
     * Whitespace is preferred; an unbroken run is hard-cut as a last resort.
     * Opaque protected tokens are never cut in half.
     */
    private fun chunkForBackend(text: String): List<String> {
        if (text.length <= MAX_BACKEND_UNIT_LENGTH) return listOf(text.trim())

        val chunks = mutableListOf<String>()
        var cursor = 0
        while (text.length - cursor > MAX_BACKEND_UNIT_LENGTH) {
            val target = cursor + MAX_BACKEND_UNIT_LENGTH
            var cut = target
            var whitespace = target
            while (whitespace > cursor + MIN_HARD_CHUNK_LENGTH &&
                !text[whitespace - 1].isWhitespace()
            ) {
                whitespace -= 1
            }
            if (whitespace > cursor + MIN_HARD_CHUNK_LENGTH) cut = whitespace
            cut = movePastProtectedToken(text, cut)

            text.substring(cursor, cut).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
            cursor = cut
            while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
        }
        text.substring(cursor).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        return chunks
    }

    private fun movePastProtectedToken(text: String, proposedCut: Int): Int {
        val open = text.lastIndexOf(PROTECTED_TOKEN_OPEN, startIndex = proposedCut - 1)
        val close = text.lastIndexOf(PROTECTED_TOKEN_CLOSE, startIndex = proposedCut - 1)
        if (open <= close) return proposedCut
        val tokenEnd = text.indexOf(PROTECTED_TOKEN_CLOSE, startIndex = proposedCut)
        return if (tokenEnd >= 0) tokenEnd + 1 else proposedCut
    }

    private data class Cut(
        val leftEnd: Int,
        val rightStart: Int,
        val sentence: Boolean,
        val priority: Int,
    )

    /**
     * Finds all legal boundaries once. Selection below advances monotonically,
     * avoiding the former repeated substring-and-rescan path on long text.
     */
    private fun findCuts(text: String): List<Cut> {
        val cuts = mutableListOf<Cut>()
        text.indices.forEach { at ->
            val terminator = text[at]
            if (terminator in SENTENCE_TERMINATORS) {
                sentenceBoundary(text, at, terminator)?.let { boundary ->
                    cuts += Cut(
                        leftEnd = boundary.first,
                        rightStart = boundary.second,
                        sentence = true,
                        priority = SENTENCE_PRIORITY,
                    )
                }
            }
        }

        MARKERS.forEachIndexed { priority, marker ->
            var at = text.indexOf(marker, ignoreCase = true)
            while (at >= 0) {
                // Drop the comma and whitespace, keep the connector itself with
                // the right clause so its relationship to the left is retained.
                val skip = if (marker.startsWith(",")) 2 else 1
                val rightStart = at + skip
                if (text.length - rightStart >= MIN_CLAUSE_LENGTH) {
                    cuts += Cut(
                        leftEnd = at,
                        rightStart = rightStart,
                        sentence = false,
                        priority = priority,
                    )
                }
                at = text.indexOf(marker, startIndex = at + 1, ignoreCase = true)
            }
        }

        return cuts.sortedWith(compareBy(Cut::leftEnd, Cut::priority, Cut::rightStart))
    }

    /** Returns the next boundary and the monotonic candidate cursor. */
    private fun nextCut(
        textLength: Int,
        candidates: List<Cut>,
        startIndex: Int,
        cursor: Int,
        sentenceOnly: Boolean,
    ): Pair<Cut, Int>? {
        var index = startIndex
        while (index < candidates.size) {
            val candidate = candidates[index]
            index += 1
            if (candidate.rightStart <= cursor) continue
            if (candidate.leftEnd - cursor < MIN_CLAUSE_LENGTH) continue
            if (textLength - candidate.rightStart < MIN_CLAUSE_LENGTH) continue
            if (sentenceOnly && !candidate.sentence) continue
            return candidate to index
        }
        return null
    }

    private fun sentenceBoundary(
        text: String,
        at: Int,
        terminator: Char,
    ): Pair<Int, Int>? {
        if (terminator == '.' && isLatinAbbreviation(text, at)) return null

        var leftEnd = at + 1
        // Treat ellipses and trailing quotes/brackets as part of the left unit.
        while (leftEnd < text.length && text[leftEnd] in REPEATED_TERMINATORS) leftEnd += 1
        while (leftEnd < text.length && text[leftEnd] in SENTENCE_CLOSERS) leftEnd += 1

        var rightStart = leftEnd
        if (terminator in LATIN_SENTENCE_TERMINATORS) {
            // A Latin full stop is a sentence boundary only when followed by
            // whitespace. Protected values have already been tokenized, while
            // this guard bounds false positives such as initials and filenames.
            if (rightStart >= text.length || !text[rightStart].isWhitespace()) return null
        }
        while (rightStart < text.length && text[rightStart].isWhitespace()) rightStart += 1
        if (text.length - rightStart < MIN_CLAUSE_LENGTH) return null
        return leftEnd to rightStart
    }

    private fun isLatinAbbreviation(text: String, at: Int): Boolean {
        var wordStart = at
        while (wordStart > 0 && text[wordStart - 1].isLetter()) wordStart -= 1
        val word = text.substring(wordStart, at).lowercase()
        return word.length == 1 || word in LATIN_ABBREVIATIONS
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

    /** Preserve sentence punctuation on the left-hand translation unit. */
    private val SENTENCE_TERMINATORS = setOf('。', '！', '？', '.', '!', '?')
    private val LATIN_SENTENCE_TERMINATORS = setOf('.', '!', '?')
    private val REPEATED_TERMINATORS = setOf('.', '!', '?', '。', '！', '？', '…')
    private val SENTENCE_CLOSERS = setOf('"', '\'', '”', '’', ')', ']', '}', '）', '］', '｝', '」', '』', '】', '》', '〉')
    private val LATIN_ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "fig", "no", "dept", "inc", "ltd",
    )

    /** Below this the model was never in trouble, so leave the text intact. */
    private const val MAX_UNSPLIT_LENGTH = 90

    /** A fragment shorter than this carries too little context to translate. */
    private const val MIN_CLAUSE_LENGTH = 20

    /** Above this, skip semantic scanning and go directly to bounded backend chunks. */
    internal const val MAX_INPUT_LENGTH = 65_536
    internal const val MAX_BACKEND_UNIT_LENGTH = 1_024
    private const val MIN_HARD_CHUNK_LENGTH = MAX_BACKEND_UNIT_LENGTH / 2
    private const val PROTECTED_TOKEN_OPEN = '⟦'
    private const val PROTECTED_TOKEN_CLOSE = '⟧'
    private const val SENTENCE_PRIORITY = -1
}
