package com.screentranslation.app.util

import java.security.MessageDigest
import java.util.Locale
import java.util.TreeMap

/**
 * Replaces translation-sensitive values with deterministic opaque tokens.
 *
 * OCR text often contains URLs, dates, prices, decimals, and software versions that are
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
        private val exactTokens = replacements.mapTo(hashSetOf()) { it.first }
        private val valuesByBody = replacements.associate { (token, value) ->
            tokenBody(token).lowercase(Locale.ROOT) to value
        }
        private val tolerantToken = replacements.firstOrNull()?.first?.let { firstToken ->
            val familyPrefix = tokenBody(firstToken).substringBeforeLast('_')
            val bodyPattern = asciiCaseInsensitivePattern(familyPrefix) + "_[0-9]{4}"
            Regex(
                pattern = """(?:(?:⟦|\[|\(|\{)\s*($bodyPattern)\s*(?:⟧|\]|\)|\})|(?<![\p{L}\p{N}_])($bodyPattern)(?![\p{L}\p{N}_]))""",
            )
        }

        val hasReplacements: Boolean
            get() = replacements.isNotEmpty()

        /** Removes only this instance's exact tokens for punctuation heuristics. */
        internal fun withoutProtectedValues(text: String = encoded): String {
            if (exactTokens.isEmpty()) return text

            return buildString(text.length) {
                var cursor = 0
                while (cursor < text.length) {
                    val open = text.indexOf(TOKEN_OPEN, cursor)
                    if (open < 0) {
                        append(text, cursor, text.length)
                        break
                    }
                    append(text, cursor, open)
                    val close = text.indexOf(TOKEN_CLOSE, open + TOKEN_OPEN.length)
                    if (close < 0) {
                        append(text, open, text.length)
                        break
                    }
                    val tokenEnd = close + TOKEN_CLOSE.length
                    val token = text.substring(open, tokenEnd)
                    if (token in exactTokens) {
                        append(' ')
                    } else {
                        append(token)
                    }
                    cursor = tokenEnd
                }
            }
        }

        /**
         * ML Kit normally preserves the token body but may normalize the
         * uncommon mathematical brackets to ASCII brackets. Match the unique
         * body with either wrapper (or no wrapper) so internal STP markers
         * never leak into user-visible translations.
         */
        fun restore(translated: String): String {
            val matcher = tolerantToken ?: return translated
            return matcher.replace(translated) { match ->
                val body = match.groups[1]?.value ?: match.groups[2]?.value
                body?.lowercase(Locale.ROOT)?.let(valuesByBody::get) ?: match.value
            }
        }
    }

    private data class Match(
        val range: IntRange,
        val value: String,
    )

    fun protect(text: String): ProtectedText {
        if (text.isEmpty()) return ProtectedText(text, text, emptyList())

        val selected = mutableListOf<Match>()
        val acceptedRanges = TreeMap<Int, Int>()
        patternLoop@ for (pattern in PATTERNS) {
            for (candidate in pattern.findAll(text)) {
                if (selected.size >= MAX_PROTECTED_VALUES) break@patternLoop
                if (!acceptedRanges.overlaps(candidate.range)) {
                    selected += Match(candidate.range, candidate.value)
                    acceptedRanges[candidate.range.first] = candidate.range.last
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

    private fun TreeMap<Int, Int>.overlaps(range: IntRange): Boolean {
        val before = floorEntry(range.first)
        if (before != null && before.value >= range.first) return true
        val after = ceilingEntry(range.first)
        return after != null && after.key <= range.last
    }

    private fun tokenBody(token: String): String =
        token.removePrefix(TOKEN_OPEN).removeSuffix(TOKEN_CLOSE)

    /** Builds ASCII-only case matching without JVM-version-dependent Unicode folding. */
    private fun asciiCaseInsensitivePattern(value: String): String = buildString(value.length * 2) {
        value.forEach { character ->
            when (character) {
                in 'a'..'z' -> append('[').append(character.uppercaseChar()).append(character).append(']')
                in 'A'..'Z' -> append('[').append(character).append(character.lowercaseChar()).append(']')
                else -> append(Regex.escape(character.toString()))
            }
        }
    }

    /** Earlier patterns win when two candidates overlap. */
    private val PATTERNS = listOf(
        Regex("""(?<![A-Za-z0-9])(?:[Hh][Tt][Tt][Pp][Ss]?://|[Ww][Ww][Ww]\.)[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]*[A-Za-z0-9/#]"""),
        Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9._%+-])"""),
        Regex("""(?<![A-Za-z0-9@._-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}(?:/[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]*[A-Za-z0-9/#])?(?![A-Za-z0-9._-])"""),
        Regex("""(?<![A-Za-z0-9_./\\-])(?:[A-Za-z]:[\\/]|/)(?:[A-Za-z0-9._ -]{1,64}[\\/]){0,8}[A-Za-z0-9._ -]{1,64}(?![A-Za-z0-9_./\\-])"""),
        Regex("""(?<![A-Za-z0-9_.-])[A-Za-z0-9_-]{1,64}\.(?:apk|aab|zip|json|xml|txt|md|pdf|png|jpe?g|webp|onnx|gguf|so|kt|java)(?![A-Za-z0-9_.-])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![A-Za-z0-9_])(?!(?:STP|SMX)_)[A-Za-z][A-Za-z0-9]{0,31}(?:_[A-Za-z0-9]{1,32}){1,8}(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE),
        Regex("""(?<!\d)(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日?)(?!\d)"""),
        Regex("""(?<!\d)(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?(?!\d)"""),
        Regex("""(?:[$€£¥￥]\s?\d+(?:[,\s]\d{3})*(?:\.\d+)?|(?:$ASCII_CURRENCY_CODE)\s?\d+(?:[,\s]\d{3})*(?:\.\d+)?|\d+(?:[,\s]\d{3})*(?:\.\d+)?\s?(?:$ASCII_CURRENCY_CODE|元|円|日元|人民币|美元|欧元|英镑))"""),
        Regex("""(?<![0-9A-Za-z.])\d+\.\d+(?![0-9A-Za-z.])"""),
        Regex("""(?<![0-9A-Za-z])[Vv]?\d+(?:\.\d+){1,4}(?:[-+][0-9A-Za-z.-]+)?(?![0-9A-Za-z])"""),
    )

    private const val ASCII_CURRENCY_CODE =
        "[Cc][Nn][Yy]|[Rr][Mm][Bb]|[Uu][Ss][Dd]|[Ee][Uu][Rr]|[Gg][Bb][Pp]|[Jj][Pp][Yy]"
    private const val MAX_PROTECTED_VALUES = 2_048
    private const val TOKEN_DIGEST_BYTES = 4
    private const val TOKEN_OPEN = "⟦"
    private const val TOKEN_CLOSE = "⟧"
}
