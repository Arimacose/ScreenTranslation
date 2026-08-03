package com.screentranslation.app.model

/** Visual language used by the app-owned setup and overlay surfaces. */
enum class UiStyle(val persistedValue: String) {
    APPLE("apple"),
    MIUIX("miuix"),
    MATERIAL3("material3"),
    ;

    companion object {
        val DEFAULT = APPLE

        fun fromPersisted(value: String?): UiStyle =
            entries.firstOrNull { it.persistedValue == value } ?: DEFAULT
    }
}

internal fun shouldApplyMaterialMonet(style: UiStyle, enabled: Boolean): Boolean =
    style == UiStyle.MATERIAL3 && enabled
