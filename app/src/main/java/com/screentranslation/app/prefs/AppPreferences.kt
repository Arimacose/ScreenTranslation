package com.screentranslation.app.prefs

import android.content.Context
import androidx.core.content.edit
import com.screentranslation.app.model.LanguageOption
import com.screentranslation.app.model.CaptureMode
import com.screentranslation.app.model.UiStyle

/**
 * Small, process-safe configuration surface shared by the activity and service.
 */
class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    var sourceLanguage: String
        get() = preferences.getString(
            KEY_SOURCE_LANGUAGE,
            LanguageOption.defaultSource.languageTag,
        ) ?: LanguageOption.defaultSource.languageTag
        set(value) {
            preferences.edit { putString(KEY_SOURCE_LANGUAGE, value) }
        }

    var targetLanguage: String
        get() = preferences.getString(
            KEY_TARGET_LANGUAGE,
            LanguageOption.defaultTarget.languageTag,
        ) ?: LanguageOption.defaultTarget.languageTag
        set(value) {
            preferences.edit { putString(KEY_TARGET_LANGUAGE, value) }
        }

    var frameIntervalMs: Long
        get() = preferences.getLong(KEY_FRAME_INTERVAL_MS, DEFAULT_FRAME_INTERVAL_MS)
            .coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        set(value) {
            preferences.edit {
                putLong(
                    KEY_FRAME_INTERVAL_MS,
                    value.coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS),
                )
            }
        }

    var captureMode: CaptureMode
        get() = CaptureMode.fromPersisted(
            preferences.getString(KEY_CAPTURE_MODE, CaptureMode.REGION.persistedValue),
        )
        set(value) {
            preferences.edit { putString(KEY_CAPTURE_MODE, value.persistedValue) }
        }

    var uiStyle: UiStyle
        get() = UiStyle.fromPersisted(
            preferences.getString(KEY_UI_STYLE, UiStyle.DEFAULT.persistedValue),
        )
        set(value) {
            preferences.edit { putString(KEY_UI_STYLE, value.persistedValue) }
        }

    var materialMonetEnabled: Boolean
        get() = preferences.getBoolean(KEY_MATERIAL_MONET_ENABLED, true)
        set(value) {
            preferences.edit { putBoolean(KEY_MATERIAL_MONET_ENABLED, value) }
        }

    fun save(
        sourceLanguage: String,
        targetLanguage: String,
        frameIntervalMs: Long,
        captureMode: CaptureMode,
    ) {
        preferences.edit {
            putString(KEY_SOURCE_LANGUAGE, sourceLanguage)
            putString(KEY_TARGET_LANGUAGE, targetLanguage)
            putLong(
                KEY_FRAME_INTERVAL_MS,
                frameIntervalMs.coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS),
            )
            putString(KEY_CAPTURE_MODE, captureMode.persistedValue)
        }
    }

    companion object {
        const val MIN_FRAME_INTERVAL_MS = 250L
        const val MAX_FRAME_INTERVAL_MS = 3_000L
        const val DEFAULT_FRAME_INTERVAL_MS = 750L

        private const val FILE_NAME = "screen_translation_preferences"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_UI_STYLE = "ui_style"
        private const val KEY_MATERIAL_MONET_ENABLED = "material_monet_enabled"
    }
}
