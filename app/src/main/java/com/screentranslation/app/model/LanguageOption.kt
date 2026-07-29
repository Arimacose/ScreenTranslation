package com.screentranslation.app.model

import android.content.Context
import androidx.annotation.StringRes
import com.screentranslation.app.R

/**
 * Languages exposed by the first Android 16 build.
 *
 * [languageTag] is deliberately a BCP-47/ML Kit compatible tag so the persisted
 * value can configure the translation model. PP-OCRv6 recognizes all supported
 * scripts through one multilingual recognition model.
 */
enum class LanguageOption(
    val languageTag: String,
    @param:StringRes val labelRes: Int,
) {
    CHINESE_SIMPLIFIED("zh", R.string.language_chinese_simplified),
    ENGLISH("en", R.string.language_english),
    JAPANESE("ja", R.string.language_japanese),
    KOREAN("ko", R.string.language_korean),
    FRENCH("fr", R.string.language_french),
    GERMAN("de", R.string.language_german),
    SPANISH("es", R.string.language_spanish),
    RUSSIAN("ru", R.string.language_russian);

    fun displayName(context: Context): String = context.getString(labelRes)

    companion object {
        val defaultSource: LanguageOption = ENGLISH
        val defaultTarget: LanguageOption = CHINESE_SIMPLIFIED
        val sourceOptions: List<LanguageOption> = entries.filterNot { it == RUSSIAN }
        val targetOptions: List<LanguageOption> = entries

        fun fromLanguageTag(
            languageTag: String?,
            fallback: LanguageOption = defaultSource,
        ): LanguageOption {
            return entries.firstOrNull {
                it.languageTag.equals(languageTag, ignoreCase = true)
            } ?: fallback
        }
    }
}
