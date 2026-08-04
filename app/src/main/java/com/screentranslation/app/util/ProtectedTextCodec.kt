package com.screentranslation.app.util

import java.security.MessageDigest

/**
 * Replaces translation-sensitive values with deterministic opaque tokens.
 *
 * OCR text often contains URLs, dates, prices, and software versions that are
 * already meaningful in the target language. Keeping them outside the model
 * prevents punctuation changes, digit grouping changes, and accidental
 * localization. The original text remains available separately for display.
 */
object ProtectedTextCodec {
    class ProtectedText internal constructor(
        val original: String,
        val encoded: String,
        private val replacements: List<Pair<String, String>>,
    ) {
        val hasReplacements: Boolean
            get() = replacements.isNotEmpty()

        fun restore(translated: String): String = replacements.fold(translated) { text, item ->
            restoreToken(text, item.first, item.second)
        }

        /**
         * ML Kit normally preserves the token body but may normalize the
         * uncommon mathematical brackets to ASCII brackets. Match the unique
         * body with either wrapper (or no wrapper) so internal STP markers
         * never leak into user-visible translations.
         */
        private fun restoreToken(text: String, token: String, value: String): String {
            val body = token.removePrefix(TOKEN_OPEN).removeSuffix(TOKEN_CLOSE)
            val escapedBody = Regex.escape(body)
            val tolerantToken = Regex(
                pattern = """(?:(?:⟦|\[|\(|\{)\s*$escapedBody\s*(?:⟧|\]|\)|\})|(?<![\p{L}\p{N}_])$escapedBody(?![\p{L}\p{N}_]))""",
                option = RegexOption.IGNORE_CASE,
            )
            return tolerantToken.replace(text) { value }
        }
    }

    private data class Match(
        val range: IntRange,
        val value: String,
    )

    fun protect(text: String): ProtectedText {
        if (text.isEmpty()) return ProtectedText(text, text, emptyList())

        val selected = mutableListOf<Match>()
        PATTERNS.forEach { pattern ->
            pattern.findAll(text).forEach { candidate ->
                if (selected.none { it.range.overlaps(candidate.range) }) {
                    selected += Match(candidate.range, candidate.value)
                }
            }
        }
        if (selected.isEmpty()) return ProtectedText(text, text, emptyList())

        selected.sortBy { it.range.first }
        val tokenPrefix = tokenPrefixFor(text)
        val replacements = selected.mapIndexed { index, match ->
            "$TOKEN_OPEN${tokenPrefix}_${index.toString().padStart(4, '0')}$TOKEN_CLOSE" to
                match.value
        }
        val encoded = buildString(text.length) {
            var cursor = 0
            selected.forEachIndexed { index, match ->
                append(text, cursor, match.range.first)
                append(replacements[index].first)
                cursor = match.range.last + 1
            }
            append(text, cursor, text.length)
        }
        return ProtectedText(text, encoded, replacements)
    }

    private fun tokenPrefixFor(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(TOKEN_DIGEST_BYTES)
            .joinToString("") { byte -> "%02x".format(byte) }
        var prefix = "STP_$digest"
        while (text.contains(prefix, ignoreCase = true)) prefix += "X"
        return prefix
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    /** Earlier patterns win when two candidates overlap. */
    private val PATTERNS = listOf(
        Regex("""(?i)\b(?:https?://|www\.)[^\s<>{}\[\]\"']*[A-Za-z0-9/#]"""),
        Regex("""(?i)(?<![\p{L}\p{N}._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}(?![\p{L}\p{N}._%+-])"""),
        Regex("""(?i)(?<![\p{L}\p{N}@._-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?:/[^\s<>{}\[\]\"']*)?(?![\p{L}\p{N}._-])"""),
        Regex("""(?<!\d)(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日?)(?!\d)"""),
        Regex("""(?i)(?:[$€£¥￥]\s?\d+(?:[,\s]\d{3})*(?:\.\d+)?|(?:CNY|RMB|USD|EUR|GBP|JPY)\s?\d+(?:[,\s]\d{3})*(?:\.\d+)?|\d+(?:[,\s]\d{3})*(?:\.\d+)?\s?(?:CNY|RMB|USD|EUR|GBP|JPY|元|円|日元|人民币|美元|欧元|英镑))"""),
        Regex("""(?i)(?<![\p{L}\p{N}])v?\d+(?:\.\d+){1,4}(?:[-+][0-9A-Z.-]+)?(?![\p{L}\p{N}])"""),
    )

    private const val TOKEN_DIGEST_BYTES = 4
    private const val TOKEN_OPEN = "⟦"
    private const val TOKEN_CLOSE = "⟧"
}
