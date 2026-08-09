package com.screentranslation.app.util

import java.util.Locale

/**
 * Conservatively restores high-value punctuation that OCR commonly drops.
 *
 * The rules are intentionally deterministic and language-aware. They only:
 * - terminate sentence-like OCR blocks and high-confidence hard line breaks;
 * - close an unambiguous unmatched opening quote/bracket at the block boundary;
 * - leave short labels, code-like text, and malformed/crossed pairs alone.
 *
 * Translation-sensitive values are encoded before any rule runs. Restoring the
 * encoded result therefore keeps URLs, dates, decimals, prices, and versions
 * byte-for-byte identical even when punctuation is inserted around them.
 */
internal object OcrPunctuationRestorer {
    fun restore(text: String, sourceLanguageTag: String): String {
        if (text.isBlank()) return text

        val language = primaryLanguage(sourceLanguageTag)
        val protected = ProtectedTextCodec.protect(text)
        val withLineBoundaries = restoreHardLineBoundaries(
            text = protected.encoded,
            protected = protected,
            language = language,
        )
        val withClosedPairs = closeUnmatchedPairs(
            text = withLineBoundaries,
            protected = protected,
            language = language,
        )
        val restored = restoreBlockEnding(
            text = withClosedPairs,
            protected = protected,
            language = language,
        )
        return protected.restore(restored)
    }

    private fun restoreHardLineBoundaries(
        text: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): String {
        val breaks = LINE_BREAK.findAll(text).toList()
        if (breaks.isEmpty()) return text

        return buildString(text.length + breaks.size) {
            var cursor = 0
            breaks.forEachIndexed { index, match ->
                val line = text.substring(cursor, match.range.first)
                val nextStart = match.range.last + 1
                val nextEnd = breaks.getOrNull(index + 1)?.range?.first ?: text.length
                val nextLine = text.substring(nextStart, nextEnd)
                append(
                    if (shouldTerminateLine(line, nextLine, protected, language)) {
                        insertTerminal(line, terminalFor(language))
                    } else {
                        line
                    },
                )
                append(match.value)
                cursor = nextStart
            }
            append(text, cursor, text.length)
        }
    }

    private fun shouldTerminateLine(
        line: String,
        nextLine: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): Boolean {
        if (hasTerminalOrExplicitBoundary(line)) return false
        val left = protected.withoutProtectedValues(line)
        val right = protected.withoutProtectedValues(nextLine)
        if (!isSentenceLike(left, language, strict = true) ||
            !isSentenceLike(right, language, strict = true)
        ) return false

        return when (language) {
            "zh", "ja" -> firstSourceLetter(right, language) != null
            else -> firstSourceLetter(right, language)?.let { Character.isUpperCase(it) } == true
        }
    }

    private fun closeUnmatchedPairs(
        text: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): String {
        if (!hasSourceLetters(protected.withoutProtectedValues(text), language)) return text

        val stack = ArrayDeque<Char>()
        for (character in text) {
            val closing = PAIRS[character]
            if (closing != null) {
                stack.addLast(closing)
            } else if (character in CLOSERS) {
                // A crossed or orphaned closer is ambiguous OCR. Preserve the
                // source rather than guessing a second repair around it.
                if (stack.lastOrNull() != character) return text
                stack.removeLast()
            }
        }

        val straightQuote = unmatchedStraightDoubleQuote(text)
        if (stack.isEmpty() && straightQuote == null) return text
        return insertBeforeTrailingWhitespace(
            text,
            buildString {
                while (stack.isNotEmpty()) append(stack.removeLast())
                if (straightQuote != null) append(straightQuote)
            },
        )
    }

    private fun unmatchedStraightDoubleQuote(text: String): Char? {
        val positions = text.indices.filter { text[it] == '"' }
        if (positions.size % 2 == 0 || positions.isEmpty()) return null
        val first = positions.first()
        val before = text.substring(0, first).trimEnd().lastOrNull()
        val after = text.substring(first + 1).trimStart().firstOrNull()
        val looksLikeOpeningQuote = (before == null || before in OPENING_CONTEXT) &&
            after?.let { Character.isLetterOrDigit(it) } == true
        return '"'.takeIf { looksLikeOpeningQuote }
    }

    private fun restoreBlockEnding(
        text: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): String {
        if (hasTerminalOrExplicitBoundary(text)) return text
        val analysis = protected.withoutProtectedValues(text)
            .trimEnd()
            .trimEnd { character -> character in TRAILING_CLOSERS }
        if (!isSentenceLike(analysis, language, strict = false)) return text
        return insertTerminal(text, terminalFor(language))
    }

    private fun isSentenceLike(text: String, language: String, strict: Boolean): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || CODE_SIGNAL.containsMatchIn(trimmed)) return false
        if (trimmed.last() in NON_SENTENCE_TRAILING) return false

        return when (language) {
            "zh" -> isChineseSentenceLike(trimmed, strict)
            "ja" -> isJapaneseSentenceLike(trimmed, strict)
            else -> isLatinSentenceLike(trimmed, strict)
        }
    }

    private fun isChineseSentenceLike(text: String, strict: Boolean): Boolean {
        val count = countScripts(text, HAN)
        if (count < if (strict) STRICT_CJK_LETTERS else MIN_ZH_LETTERS) return false
        return strict || ZH_SENTENCE_SIGNALS.any(text::contains) ||
            ZH_SENTENCE_ENDINGS.any(text::endsWith)
    }

    private fun isJapaneseSentenceLike(text: String, strict: Boolean): Boolean {
        val kana = countScripts(text, HIRAGANA, KATAKANA)
        val cjk = kana + countScripts(text, HAN)
        if (kana == 0 || cjk < if (strict) STRICT_CJK_LETTERS else MIN_JA_LETTERS) return false
        return strict || JA_SENTENCE_ENDINGS.any(text::endsWith)
    }

    private fun isLatinSentenceLike(text: String, strict: Boolean): Boolean {
        val words = LATIN_WORD.findAll(text).map { it.value }.toList()
        val letterCount = words.sumOf { word -> word.count { Character.isLetter(it) } }
        val minimumWords = if (strict) STRICT_LATIN_WORDS else MIN_LATIN_WORDS
        val minimumLetters = if (strict) STRICT_LATIN_LETTERS else MIN_LATIN_LETTERS
        if (words.size < minimumWords || letterCount < minimumLetters) return false

        // Three-word noun phrases are common settings labels. A short block is
        // treated as a sentence only when it carries a small grammatical signal.
        if (!strict && words.size <= SHORT_LATIN_LABEL_WORD_LIMIT) {
            val normalized = words.map { it.lowercase(Locale.ROOT) }
            val hasSignal = normalized.any { word ->
                word in SHORT_SENTENCE_SIGNALS ||
                    word.endsWith("ed") ||
                    word.endsWith("ing")
            }
            if (!hasSignal) return false
        }
        return true
    }

    private fun firstSourceLetter(text: String, language: String): Char? = text.firstOrNull { char ->
        when (language) {
            "zh" -> Character.UnicodeScript.of(char.code) == HAN
            "ja" -> Character.UnicodeScript.of(char.code) in setOf(HAN, HIRAGANA, KATAKANA)
            else -> Character.UnicodeScript.of(char.code) == LATIN && Character.isLetter(char)
        }
    }

    private fun hasSourceLetters(text: String, language: String): Boolean =
        firstSourceLetter(text, language) != null

    private fun countScripts(text: String, vararg scripts: Character.UnicodeScript): Int {
        val accepted = scripts.toSet()
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.UnicodeScript.of(codePoint) in accepted) count += 1
            index += Character.charCount(codePoint)
        }
        return count
    }

    private fun hasTerminalOrExplicitBoundary(text: String): Boolean {
        var index = text.indexOfLast { !it.isWhitespace() }
        while (index >= 0 && text[index] in TRAILING_CLOSERS) index -= 1
        return index < 0 || text[index] in TERMINAL_OR_BOUNDARY
    }

    private fun insertTerminal(text: String, terminal: Char): String {
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        if (end <= 0) return text

        var insertAt = end
        while (insertAt > 0 && text[insertAt - 1] in QUOTE_CLOSERS) insertAt -= 1
        return text.substring(0, insertAt) + terminal + text.substring(insertAt)
    }

    private fun insertBeforeTrailingWhitespace(text: String, suffix: String): String {
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return text.substring(0, end) + suffix + text.substring(end)
    }

    private fun primaryLanguage(languageTag: String): String = languageTag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase(Locale.ROOT)

    private fun terminalFor(language: String): Char = if (language in CJK_LANGUAGES) '。' else '.'

    private val LINE_BREAK = Regex("\\r\\n|\\r|\\n")
    private val LATIN_WORD = Regex("[\\p{L}]+(?:['’][\\p{L}]+)?")
    private val CODE_SIGNAL = Regex("(?:[{}=]|->|::|[/\\\\]{2,}|^[A-Z0-9_]{2,}$)")
    private val PAIRS = linkedMapOf(
        '(' to ')', '[' to ']', '{' to '}',
        '（' to '）', '［' to '］', '｛' to '｝',
        '“' to '”', '‘' to '’',
        '「' to '」', '『' to '』',
        '【' to '】', '《' to '》', '〈' to '〉',
    )
    private val CLOSERS = PAIRS.values.toSet()
    private val QUOTE_CLOSERS = setOf('”', '’', '"', '」', '』')
    private val TRAILING_CLOSERS = CLOSERS + '"'
    private val OPENING_CONTEXT = setOf('(', '[', '{', '（', '［', '｛', '“', '‘', '「', '『')
    private val TERMINAL_OR_BOUNDARY = setOf('.', '!', '?', '。', '！', '？', '…', ':', '：', ';', '；', ',', '，')
    private val NON_SENTENCE_TRAILING = setOf('/', '\\', '|', '=', '+', '-', '_', ':', '：', ';', '；', ',', '，')
    private val CJK_LANGUAGES = setOf("zh", "ja")
    private val SHORT_SENTENCE_SIGNALS = setOf(
        "a", "an", "the", "i", "you", "he", "she", "it", "we", "they",
        "me", "him", "her", "us", "them", "is", "am", "are", "was", "were",
        "be", "been", "do", "does", "did", "will", "would", "can", "could",
        "should", "must", "open", "close", "select", "choose", "tap", "press",
        "come", "go", "stop", "start", "try", "wait", "run", "save", "delete",
    )
    private val ZH_SENTENCE_SIGNALS = setOf(
        "是", "有", "会", "将", "已", "正在", "可以", "需要", "请", "打开", "关闭",
        "选择", "点击", "开始", "停止", "保持", "支持", "使用", "显示", "保存", "删除",
        "允许", "确认", "检查", "安装", "访问", "查看", "翻译", "处理", "完成", "阅读",
    )
    private val ZH_SENTENCE_ENDINGS = setOf("了", "吗", "呢", "吧", "着", "过")
    private val JA_SENTENCE_ENDINGS = setOf(
        "です", "ます", "でした", "ました", "ません", "ない", "ください", "する", "した",
    )
    private val HAN = Character.UnicodeScript.HAN
    private val HIRAGANA = Character.UnicodeScript.HIRAGANA
    private val KATAKANA = Character.UnicodeScript.KATAKANA
    private val LATIN = Character.UnicodeScript.LATIN

    private const val MIN_LATIN_WORDS = 3
    private const val MIN_LATIN_LETTERS = 8
    private const val STRICT_LATIN_WORDS = 4
    private const val STRICT_LATIN_LETTERS = 14
    private const val MIN_ZH_LETTERS = 6
    private const val MIN_JA_LETTERS = 5
    private const val STRICT_CJK_LETTERS = 8
    private const val SHORT_LATIN_LABEL_WORD_LIMIT = 6
}
