package com.screentranslation.app.util

import java.util.Locale

/**
 * Keeps OCR output aligned with the language pair selected by the user.
 *
 * PP-OCRv6 is intentionally multilingual, so recognition itself cannot be
 * restricted to the selected source language. This boundary removes lines
 * that contain no meaningful source-script text before they reach any local
 * or online translation backend. Values protected by [ProtectedTextCodec]
 * remain in a real sentence, but do not make a URL/date/price-only line look
 * translatable.
 */
internal object SourceTextFilter {
    fun filter(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String? {
        val source = primaryLanguage(sourceLanguageTag)
        val target = primaryLanguage(targetLanguageTag)
        if (text.isBlank() || source == target) return null

        return text.lineSequence()
            .mapNotNull { line -> filterLine(line, source, target) }
            .joinToString(" ")
            .trim()
            .takeIf(String::isNotEmpty)
    }

    fun filterBlocks(
        blocks: List<String>,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): List<String> = blocks.mapNotNull { block ->
        filter(block, sourceLanguageTag, targetLanguageTag)
    }

    private fun filterLine(line: String, source: String, target: String): String? {
        val original = line.trim()
        if (original.isEmpty()) return null

        val protected = ProtectedTextCodec.protect(original)
        if (containsDistinctTargetScript(
            text = protected.encoded,
            source = source,
            target = target,
        )) return null

        val informative = PROTECTED_TOKEN.replace(protected.encoded, " ")
        if (!containsMeaningfulSourceScript(informative, source)) return null

        return protected.restore(normalizeWhitespace(protected.encoded))
            .trim()
            .takeIf(String::isNotEmpty)
    }

    /**
     * A target-language UI sentence can contain Latin product names. Translating
     * only that embedded name still creates a misleading overlay, so the entire
     * already-target-language line is skipped. Japanese keeps Han because Kanji
     * is part of the source language; the mandatory-kana rule below is the
     * conservative Japanese/Chinese disambiguator.
     */
    private fun containsDistinctTargetScript(
        text: String,
        source: String,
        target: String,
    ): Boolean {
        val scriptsToReject = when (target) {
            "zh" -> if (source == "zh" || source == "ja") emptySet() else setOf(HAN)
            "ja" -> if (source == "ja") emptySet() else setOf(HIRAGANA, KATAKANA)
            "ko" -> if (source == "ko") emptySet() else setOf(HANGUL)
            "ru" -> if (source == "ru") emptySet() else setOf(CYRILLIC)
            else -> emptySet()
        }
        if (scriptsToReject.isEmpty()) return false

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) in scriptsToReject
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun containsMeaningfulSourceScript(text: String, source: String): Boolean {
        var latin = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var cyrillic = 0
        var anyLetters = 0

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                anyLetters += 1
                when (Character.UnicodeScript.of(codePoint)) {
                    LATIN -> latin += 1
                    HAN -> han += 1
                    HIRAGANA, KATAKANA -> kana += 1
                    HANGUL -> hangul += 1
                    CYRILLIC -> cyrillic += 1
                    else -> Unit
                }
            }
            index += Character.charCount(codePoint)
        }

        return when (source) {
            "en", "fr", "de", "es" -> latin >= MIN_ALPHABETIC_LETTERS
            "zh" -> han >= 1
            // Pure Kanji is indistinguishable from Chinese without a language
            // model, so a kana signal is required for the conservative path.
            "ja" -> kana >= 1
            "ko" -> hangul >= 1
            "ru" -> cyrillic >= MIN_ALPHABETIC_LETTERS
            else -> anyLetters >= MIN_ALPHABETIC_LETTERS
        }
    }

    private fun primaryLanguage(languageTag: String): String = languageTag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase(Locale.ROOT)

    private fun normalizeWhitespace(text: String): String = text.replace(WHITESPACE, " ")

    private const val MIN_ALPHABETIC_LETTERS = 2
    private val WHITESPACE = Regex("\\s+")
    private val PROTECTED_TOKEN = Regex("⟦STP_[0-9a-f]{8}X*_\\d{4}⟧")
    private val LATIN = Character.UnicodeScript.LATIN
    private val HAN = Character.UnicodeScript.HAN
    private val HIRAGANA = Character.UnicodeScript.HIRAGANA
    private val KATAKANA = Character.UnicodeScript.KATAKANA
    private val HANGUL = Character.UnicodeScript.HANGUL
    private val CYRILLIC = Character.UnicodeScript.CYRILLIC
}
