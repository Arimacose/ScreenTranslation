package com.screentranslation.app.util

/**
 * Admits OCR blocks that contain at least one source-language segment.
 *
 * The original text is retained for the stable/source-match key. Translation
 * requests are produced later by [SegmentedTextPlanner], so mixed target text
 * and protected spans never disappear from the user-visible original.
 */
internal object SourceTextFilter {
    fun filter(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        policy: SourceRoutingPolicy = SourceRoutingPolicy.SMART_MIXED,
        neighboringTextHasKana: Boolean = false,
    ): String? {
        if (text.isBlank()) return null
        val normalized = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .replace(WHITESPACE, " ")
            .trim()
        if (normalized.isEmpty()) return null

        val plan = SegmentedTextPlanner.plan(
            text = normalized,
            sourceLanguageTag = sourceLanguageTag,
            targetLanguageTag = targetLanguageTag,
            policy = policy,
            neighboringTextHasKana = neighboringTextHasKana,
        )
        return normalized.takeIf { plan.translatedSpanCount > 0 }
    }

    fun filterBlocks(
        blocks: List<String>,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        policy: SourceRoutingPolicy = SourceRoutingPolicy.SMART_MIXED,
    ): List<String> {
        val neighboringKana = blocks.any(::containsKana)
        return blocks.mapNotNull { block ->
            filter(
                text = block,
                sourceLanguageTag = sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
                policy = policy,
                neighboringTextHasKana = neighboringKana,
            )
        }
    }

    private fun containsKana(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> return true
                else -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private val WHITESPACE = Regex("\\s+")
}
