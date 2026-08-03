package com.screentranslation.app.ui

import android.app.Activity
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.screentranslation.app.R
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.model.shouldApplyMaterialMonet
import com.screentranslation.app.prefs.AppPreferences

/** Applies the persisted visual language before an Activity inflates any Views. */
object UiStyleController {
    fun apply(activity: Activity): UiStyle {
        val preferences = AppPreferences(activity)
        val style = preferences.uiStyle
        activity.setTheme(themeResource(style))
        if (shouldApplyMaterialMonet(style, preferences.materialMonetEnabled)) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
        return style
    }

    @StyleRes
    internal fun themeResource(style: UiStyle): Int = when (style) {
        UiStyle.APPLE -> R.style.Theme_ScreenTranslation_Apple
        UiStyle.MIUIX -> R.style.Theme_ScreenTranslation_Miuix
        UiStyle.MATERIAL3 -> R.style.Theme_ScreenTranslation_Material3
    }
}
