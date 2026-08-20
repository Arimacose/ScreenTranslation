package com.screentranslation.app.util

import java.security.MessageDigest
import java.util.Locale

enum class SourceRoutingPolicy {
    SMART_MIXED,
    STRICT_TARGET_SKIP,
    EXPLICIT_SOURCE,
}

enum class TextSpanKind {
    TRANSLATE,
    PRESERVE_TARGET,
    PRESERVE_PROTECTED,
    SEPARATOR,
}

data class PlannedTextSpan(
    val id: Int,
    val kind: TextSpanKind,
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

/**
 * A deterministic, source-matched translation request.
 *
 * Preserved target-language and protected values are represented by tokens in
 * [requestText]. [restore] accepts a result only when every expected token is
 * present exactly once and no token from this plan's family is unexpected.
 */
class SegmentedTextPlan internal constructor(
    val originalText: String,
    val requestText: String,
    val spans: List<PlannedTextSpan>,
    private val protectedText: ProtectedTextCodec.ProtectedText,
    private val tokenValues: LinkedHashMap<String, String>,
    private val tokenPrefix: String,
) {
    val translatedSpanCount: Int = spans.count { it.kind == TextSpanKind.TRANSLATE }

    val requestIdentity: String = stableDigest(requestText)

    fun restore(translatedRequestText: String): String {
        require(translatedSpanCount > 0) { "Translation plan contains no translatable spans" }
        val observed = PLAN_TOKEN.findAll(translatedRequestText).map { it.value }.toList()
        require(observed.size == tokenValues.size) { "Preserved translation token count changed" }
        require(observed.toSet().size == observed.size) { "Preserved translation token was duplicated" }
        require(observed.toSet() == tokenValues.keys) { "Preserved translation token set changed" }
        require(observed.all { it.startsWith("⟦${tokenPrefix}_") }) {
            "Unexpected translation token family"
        }

        var restored = translatedRequestText
        tokenValues.forEach { (token, value) -> restored = restored.replace(token, value) }
        require(!PLAN_TOKEN.containsMatchIn(restored)) { "Unexpected translation token remained" }
        return protectedText.restore(restored)
    }

    companion object {
        private val PLAN_TOKEN = Regex("⟦SMX_[0-9a-f]{8}X*_[0-9]{4}⟧")

        private fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Script-aware, bounded planner shared by Lite, Full, and Online. */
object SegmentedTextPlanner {
    fun plan(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        policy: SourceRoutingPolicy = SourceRoutingPolicy.SMART_MIXED,
        neighboringTextHasKana: Boolean = false,
    ): SegmentedTextPlan {
        val source = primaryLanguage(sourceLanguageTag)
        val target = primaryLanguage(targetLanguageTag)
        val protected = ProtectedTextCodec.protect(text)
        if (text.isBlank() || source == target) {
            return emptyPlan(text, protected)
        }

        val strictSkip = policy == SourceRoutingPolicy.STRICT_TARGET_SKIP &&
            containsTargetScript(protected.withoutProtectedValues(), target)
        val hasKanaContext = neighboringTextHasKana || containsKana(protected.withoutProtectedValues())
        val spans = mutableListOf<PlannedTextSpan>()
        scanEncoded(
            encoded = protected.encoded,
            originalText = text,
            source = source,
            target = target,
            policy = policy,
            strictSkip = strictSkip,
            hasKanaContext = hasKanaContext,
            destination = spans,
        )

        val translateCount = spans.count { it.kind == TextSpanKind.TRANSLATE }
        if (translateCount == 0) {
            return SegmentedTextPlan(text, "", spans, protected, linkedMapOf(), "SMX_00000000")
        }

        var prefix = "SMX_${digestPrefix(text)}"
        while (text.contains(prefix, ignoreCase = true)) prefix += "X"
        val tokenValues = linkedMapOf<String, String>()
        val request = buildString(protected.encoded.length) {
            spans.forEach { span ->
                if (span.kind == TextSpanKind.TRANSLATE) {
                    append(span.text)
                } else {
                    val token = "⟦${prefix}_${tokenValues.size.toString().padStart(4, '0')}⟧"
                    tokenValues[token] = span.text
                    append(token)
                }
            }
        }
        return SegmentedTextPlan(text, request, spans, protected, tokenValues, prefix)
    }

    private fun scanEncoded(
        encoded: String,
        originalText: String,
        source: String,
        target: String,
        policy: SourceRoutingPolicy,
        strictSkip: Boolean,
        hasKanaContext: Boolean,
        destination: MutableList<PlannedTextSpan>,
    ) {
        var cursor = 0
        while (cursor < encoded.length) {
            if (destination.size >= MAX_SPANS - 1) {
                appendSpan(
                    destination,
                    TextSpanKind.PRESERVE_TARGET,
                    cursor,
                    encoded.length,
                    encoded.substring(cursor),
                )
                return
            }
            if (encoded.startsWith(PROTECTED_OPEN, cursor)) {
                val close = encoded.indexOf('⟧', cursor + PROTECTED_OPEN.length)
                if (close >= 0) {
                    appendSpan(
                        destination,
                        TextSpanKind.PRESERVE_PROTECTED,
                        cursor,
                        close + 1,
                        encoded.substring(cursor, close + 1),
                    )
                    cursor = close + 1
                    continue
                }
            }

            val codePoint = encoded.codePointAt(cursor)
            val width = Character.charCount(codePoint)
            val classification = classify(codePoint)
            val kind = when {
                strictSkip -> TextSpanKind.PRESERVE_TARGET
                classification == ScriptClass.SEPARATOR ->
                    destination.lastOrNull()?.kind ?: TextSpanKind.SEPARATOR
                classification == ScriptClass.DIGIT -> TextSpanKind.PRESERVE_PROTECTED
                isEligible(
                    classification = classification,
                    text = encoded.substring(cursor, cursor + width),
                    source = source,
                    target = target,
                    policy = policy,
                    hasKanaContext = hasKanaContext,
                    originalText = originalText,
                ) -> TextSpanKind.TRANSLATE
                else -> TextSpanKind.PRESERVE_TARGET
            }
            appendSpan(
                destination,
                kind,
                cursor,
                cursor + width,
                encoded.substring(cursor, cursor + width),
            )
            cursor += width
        }
    }

    private fun appendSpan(
        destination: MutableList<PlannedTextSpan>,
        kind: TextSpanKind,
        start: Int,
        endExclusive: Int,
        text: String,
    ) {
        val previous = destination.lastOrNull()
        if (previous != null && previous.kind == kind && previous.endExclusive == start) {
            destination[destination.lastIndex] = previous.copy(
                endExclusive = endExclusive,
                text = previous.text + text,
            )
        } else {
            destination += PlannedTextSpan(
                id = destination.size,
                kind = kind,
                start = start,
                endExclusive = endExclusive,
                text = text,
            )
        }
    }

    private fun isEligible(
        classification: ScriptClass,
        text: String,
        source: String,
        target: String,
        policy: SourceRoutingPolicy,
        hasKanaContext: Boolean,
        originalText: String,
    ): Boolean {
        if (matchesLanguage(classification, target) && source != target) {
            if (source != "ja" || classification != ScriptClass.HAN) return false
        }
        return when (source) {
            "en", "fr", "de", "es", "it", "pt" -> classification == ScriptClass.LATIN
            "zh" -> classification == ScriptClass.HAN
            "ja" -> when (classification) {
                ScriptClass.KANA -> true
                ScriptClass.HAN -> policy == SourceRoutingPolicy.EXPLICIT_SOURCE ||
                    hasKanaContext || PURE_KANJI_UI.any(originalText::contains) ||
                    PURE_KANJI_UI.any(text::contains)
                else -> false
            }
            "ko" -> classification == ScriptClass.HANGUL
            "ru", "uk" -> classification == ScriptClass.CYRILLIC
            else -> classification in SEMANTIC_CLASSES
        }
    }

    private fun containsTargetScript(text: String, target: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (matchesLanguage(classify(codePoint), target)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun containsKana(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (classify(codePoint) == ScriptClass.KANA) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun matchesLanguage(classification: ScriptClass, language: String): Boolean = when (language) {
        "zh" -> classification == ScriptClass.HAN
        "ja" -> classification == ScriptClass.HAN || classification == ScriptClass.KANA
        "ko" -> classification == ScriptClass.HANGUL
        "ru", "uk" -> classification == ScriptClass.CYRILLIC
        "en", "fr", "de", "es", "it", "pt" -> classification == ScriptClass.LATIN
        else -> false
    }

    private fun classify(codePoint: Int): ScriptClass {
        if (Character.isDigit(codePoint)) return ScriptClass.DIGIT
        if (!Character.isLetter(codePoint)) return ScriptClass.SEPARATOR
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.LATIN -> ScriptClass.LATIN
            Character.UnicodeScript.HAN -> ScriptClass.HAN
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            -> ScriptClass.KANA
            Character.UnicodeScript.HANGUL -> ScriptClass.HANGUL
            Character.UnicodeScript.CYRILLIC -> ScriptClass.CYRILLIC
            else -> ScriptClass.OTHER_LETTER
        }
    }

    private fun emptyPlan(
        text: String,
        protected: ProtectedTextCodec.ProtectedText,
    ) = SegmentedTextPlan(
        originalText = text,
        requestText = "",
        spans = if (text.isEmpty()) emptyList() else listOf(
            PlannedTextSpan(0, TextSpanKind.PRESERVE_TARGET, 0, text.length, text),
        ),
        protectedText = protected,
        tokenValues = linkedMapOf(),
        tokenPrefix = "SMX_00000000",
    )

    private fun primaryLanguage(languageTag: String): String = languageTag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase(Locale.ROOT)

    private fun digestPrefix(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { byte -> "%02x".format(byte) }

    private enum class ScriptClass {
        LATIN,
        HAN,
        KANA,
        HANGUL,
        CYRILLIC,
        OTHER_LETTER,
        DIGIT,
        SEPARATOR,
    }

    private val SEMANTIC_CLASSES = setOf(
        ScriptClass.LATIN,
        ScriptClass.HAN,
        ScriptClass.KANA,
        ScriptClass.HANGUL,
        ScriptClass.CYRILLIC,
        ScriptClass.OTHER_LETTER,
    )
    private val PURE_KANJI_UI = setOf(
        "設定", "確認", "開始", "終了", "保存", "削除", "戻る", "次へ", "読込", "選択",
    )
    private const val PROTECTED_OPEN = "⟦STP_"
    private const val MAX_SPANS = 4_096
}
