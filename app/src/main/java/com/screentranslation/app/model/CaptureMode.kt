package com.screentranslation.app.model

enum class CaptureMode(val persistedValue: String) {
    REGION("region"),
    FULL_SCREEN_INCREMENTAL("full_screen_incremental"),
    ;

    companion object {
        fun fromPersisted(value: String?): CaptureMode =
            entries.firstOrNull { it.persistedValue == value } ?: REGION
    }
}
