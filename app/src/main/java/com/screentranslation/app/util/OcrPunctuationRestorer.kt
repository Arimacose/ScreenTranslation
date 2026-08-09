package com.screentranslation.app.util

import java.util.Locale

/**
 * Conservatively restores high-value punctuation that OCR commonly drops.
 *
 * The rules are intentionally deterministic and language-aware. They only:
 * - terminate high-confidence standalone OCR blocks and paragraph breaks;
 * - close an unambiguous unmatched opening quote/bracket at the block boundary;
 * - leave short labels, code-like text, and malformed/crossed pairs alone.
 *
 * Translation-sensitive values are encoded before any rule runs. Restoring the
 * encoded result therefore keeps URLs, dates, decimals, prices, and versions
 * byte-for-byte identical even when punctuation is inserted around them.
 */
internal object OcrPunctuationRestorer {
    fun restore(text: String, sourceLanguageTag: String): String {
        return restore(text, sourceLanguageTag, allowSentenceEnding = true)
    }

    /** Restores each OCR detector block independently using the same rules. */
    fun restoreBlocks(blocks: List<String>, sourceLanguageTag: String): List<String> {
        return blocks.map { block ->
            restore(block, sourceLanguageTag, allowSentenceEnding = true)
        }
    }

    fun restore(
        text: String,
        sourceLanguageTag: String,
        allowSentenceEnding: Boolean,
    ): String {
        if (text.isBlank() || text.length > MAX_INPUT_LENGTH) return text

        val language = primaryLanguage(sourceLanguageTag)
        val protected = ProtectedTextCodec.protect(text)
        val withLineBoundaries = if (allowSentenceEnding) {
            restoreParagraphBoundaries(
                text = protected.encoded,
                protected = protected,
                language = language,
            )
        } else {
            protected.encoded
        }
        val withClosedPairs = closeUnmatchedPairs(
            text = withLineBoundaries,
            protected = protected,
            language = language,
        )
        val restored = if (allowSentenceEnding && !containsSoftLineBreak(withClosedPairs)) {
            restoreBlockEnding(
                text = withClosedPairs,
                protected = protected,
                language = language,
            )
        } else {
            withClosedPairs
        }
        return protected.restore(restored)
    }

    private fun restoreParagraphBoundaries(
        text: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): String {
        val breaks = PARAGRAPH_BREAK.findAll(text).toList()
        if (breaks.isEmpty()) return text

        return buildString(text.length + breaks.size) {
            var cursor = 0
            breaks.forEachIndexed { index, match ->
                val line = text.substring(cursor, match.range.first)
                val nextStart = match.range.last + 1
                val nextEnd = breaks.getOrNull(index + 1)?.range?.first ?: text.length
                val nextLine = text.substring(nextStart, nextEnd)
                append(
                    if (shouldTerminateParagraph(line, nextLine, protected, language)) {
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

    private fun shouldTerminateParagraph(
        line: String,
        nextLine: String,
        protected: ProtectedTextCodec.ProtectedText,
        language: String,
    ): Boolean {
        if (hasTerminalOrExplicitBoundary(line)) return false
        val left = protected.withoutProtectedValues(line)
        val right = protected.withoutProtectedValues(nextLine)
        if (!isSentenceLike(left, language, strict = true) ||
            !hasStrongTerminalForm(left, language) ||
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
        return isChineseImperative(text) ||
            ZH_SENTENCE_ENDINGS.any(text::endsWith) ||
            (ZH_CLAUSE_SIGNALS.any(text::contains) && ZH_PREDICATE_ENDINGS.any(text::endsWith)) ||
            ZH_VALUE_PREDICATE_ENDINGS.any(text::endsWith)
    }

    private fun isJapaneseSentenceLike(text: String, strict: Boolean): Boolean {
        val kana = countScripts(text, HIRAGANA, KATAKANA)
        val cjk = kana + countScripts(text, HAN)
        if (kana == 0 || cjk < if (strict) STRICT_CJK_LETTERS else MIN_JA_LETTERS) return false
        return JA_SENTENCE_ENDINGS.any(text::endsWith)
    }

    private fun isLatinSentenceLike(text: String, strict: Boolean): Boolean {
        val words = LATIN_WORD.findAll(text).map { it.value }.toList()
        val letterCount = words.sumOf { word -> word.count { Character.isLetter(it) } }
        val minimumWords = if (strict) STRICT_LATIN_WORDS else MIN_LATIN_WORDS
        val minimumLetters = if (strict) STRICT_LATIN_LETTERS else MIN_LATIN_LETTERS
        if (words.size < minimumWords || letterCount < minimumLetters) return false

        val normalized = words.map { it.lowercase(Locale.ROOT) }
        val withoutPoliteness = normalized.dropWhile { it == "please" }
        if (withoutPoliteness.take(2) == listOf("open", "source")) return false

        val hasFiniteClause = normalized.withIndex().any { (index, word) ->
            index > 0 && (
                word in LATIN_AUXILIARIES ||
                    (word in LATIN_FINITE_VERBS &&
                        (index >= 2 || normalized.take(index).any(LATIN_SUBJECT_LEADS::contains)))
                )
        }
        val imperativeOffset = if (normalized.firstOrNull() == "please") 1 else 0
        val imperative = normalized.getOrNull(imperativeOffset) in LATIN_IMPERATIVE_VERBS &&
            (imperativeOffset == 1 || normalized.getOrNull(1) in LATIN_IMPERATIVE_OBJECT_LEADS)
        return hasFiniteClause || imperative
    }

    private fun isChineseImperative(text: String): Boolean =
        ZH_IMPERATIVE_PREFIXES.any(text::startsWith) ||
            (text.startsWith("请在") && ZH_IMPERATIVE_VERBS.any(text::contains))

    private fun hasStrongTerminalForm(text: String, language: String): Boolean {
        val trimmed = text.trim()
        return when (language) {
            "zh" -> ZH_SENTENCE_ENDINGS.any(trimmed::endsWith) ||
                (ZH_CLAUSE_SIGNALS.any(trimmed::contains) &&
                    ZH_PREDICATE_ENDINGS.any(trimmed::endsWith))
            "ja" -> JA_SENTENCE_ENDINGS.any(trimmed::endsWith)
            else -> {
                val words = LATIN_WORD.findAll(trimmed)
                    .map { it.value.lowercase(Locale.ROOT) }
                    .toList()
                val last = words.lastOrNull()
                val previous = words.getOrNull(words.lastIndex - 1)
                last in LATIN_STRONG_FINAL_VERBS ||
                    (previous in LATIN_AUXILIARIES && last in LATIN_STRONG_PREDICATES)
            }
        }
    }

    private fun containsSoftLineBreak(text: String): Boolean {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return SINGLE_LINE_BREAK.containsMatchIn(normalized)
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

    private val PARAGRAPH_BREAK = Regex("(?:\\r\\n|\\r|\\n)[ \\t]*(?:\\r\\n|\\r|\\n)+")
    private val SINGLE_LINE_BREAK = Regex("(?<!\\n)\\n(?!\\n)")
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
    private val LATIN_AUXILIARIES = setOf(
        "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would",
        "can", "could", "shall", "should", "may", "might", "must",
    )
    private val LATIN_FINITE_VERBS = setOf(
        "agree", "agrees", "agreed", "arrive", "arrives", "arrived",
        "begin", "begins", "began", "complete", "completes", "completed",
        "continue", "continues", "continued", "cross", "crosses", "crossed",
        "end", "ends", "ended", "fail", "fails", "failed", "finish", "finishes", "finished",
        "inspect", "inspects", "inspected", "keep", "keeps", "kept",
        "preserve", "preserves", "preserved", "record", "records", "recorded",
        "remain", "remains", "remained", "reopen", "reopens", "reopened",
        "report", "reports", "reported", "run", "runs", "ran", "start", "starts", "started",
        "stop", "stops", "stopped", "succeed", "succeeds", "succeeded",
        "work", "works", "worked",
    )
    private val LATIN_IMPERATIVE_VERBS = setOf(
        "choose", "close", "confirm", "delete", "download", "install", "open",
        "press", "read", "save", "select", "tap", "use", "visit", "wait",
    )
    private val LATIN_IMPERATIVE_OBJECT_LEADS = setOf(
        "a", "an", "the", "this", "that", "these", "those", "your", "my", "our",
    )
    private val LATIN_SUBJECT_LEADS = LATIN_IMPERATIVE_OBJECT_LEADS + setOf(
        "i", "you", "he", "she", "it", "we", "they", "there",
    )
    private val LATIN_STRONG_FINAL_VERBS = setOf(
        "agreed", "arrived", "began", "completed", "continued", "ended", "failed",
        "finished", "passed", "remained", "reported", "started", "stopped", "succeeded",
    )
    private val LATIN_STRONG_PREDICATES = setOf(
        "active", "available", "closed", "complete", "done", "finished", "open",
        "ready", "running", "stable", "stopped", "working",
    )
    private val ZH_CLAUSE_SIGNALS = setOf(
        "已经", "正在", "仍然", "依然", "将会", "会", "将", "可以", "必须", "需要", "均已",
    )
    private val ZH_IMPERATIVE_PREFIXES = setOf(
        "请打开", "请关闭", "请选择", "请点击", "请安装", "请访问",
        "请查看", "请阅读", "请确认", "请等待", "请保持", "请使用", "请下载",
    )
    private val ZH_IMPERATIVE_VERBS = setOf(
        "打开", "关闭", "选择", "点击", "安装", "访问", "查看", "阅读", "确认", "使用", "下载",
    )
    private val ZH_PREDICATE_ENDINGS = setOf(
        "开始", "停止", "完成", "结束", "就绪", "生效", "失败", "成功", "解除", "恢复", "继续",
    )
    private val ZH_VALUE_PREDICATE_ENDINGS = setOf("为", "是", "达到", "约为", "等于", "保持在")
    private val ZH_SENTENCE_ENDINGS = setOf("了", "吗", "呢", "吧", "着", "过")
    private val JA_SENTENCE_ENDINGS = setOf(
        "です", "ですか", "でした", "だった", "でしょう", "ます", "ますか",
        "ました", "ません", "ませんでした", "ない", "なかった", "ください", "した",
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
    internal const val MAX_INPUT_LENGTH = 65_536
}
