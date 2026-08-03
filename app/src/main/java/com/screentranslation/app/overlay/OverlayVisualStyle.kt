package com.screentranslation.app.overlay

import android.content.Context
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.prefs.AppPreferences

internal data class OverlayVisualStyle(
    val panelColor: Int,
    val panelStrokeColor: Int,
    val accentColor: Int,
    val statusTextColor: Int,
    val originalTextColor: Int,
    val translationTextColor: Int,
    val attributionTextColor: Int,
    val labelColor: Int,
    val labelStrokeColor: Int,
    val labelTextColor: Int,
    val selectionFillColor: Int,
    val instructionColor: Int,
    val panelCornerDp: Int,
    val labelCornerDp: Int,
    val controlCornerDp: Int,
)

internal fun overlayVisualStyle(
    uiStyle: UiStyle,
    materialDynamicAccent: Int? = null,
): OverlayVisualStyle {
    val accent = when (uiStyle) {
        UiStyle.APPLE -> rgb(10, 132, 255)
        UiStyle.MIUIX -> rgb(52, 130, 255)
        UiStyle.MATERIAL3 -> materialDynamicAccent ?: rgb(208, 188, 255)
    }
    return when (uiStyle) {
        UiStyle.APPLE -> OverlayVisualStyle(
            panelColor = argb(244, 28, 28, 30),
            panelStrokeColor = argb(170, 72, 72, 74),
            accentColor = accent,
            statusTextColor = rgb(191, 219, 254),
            originalTextColor = rgb(229, 229, 234),
            translationTextColor = rgb(255, 255, 255),
            attributionTextColor = rgb(174, 174, 178),
            labelColor = argb(232, 28, 28, 30),
            labelStrokeColor = accent,
            labelTextColor = rgb(255, 255, 255),
            selectionFillColor = withAlpha(accent, 28),
            instructionColor = argb(210, 28, 28, 30),
            panelCornerDp = 20,
            labelCornerDp = 10,
            controlCornerDp = 12,
        )

        UiStyle.MIUIX -> OverlayVisualStyle(
            panelColor = argb(246, 23, 23, 26),
            panelStrokeColor = argb(160, 68, 72, 82),
            accentColor = accent,
            statusTextColor = rgb(215, 232, 255),
            originalTextColor = rgb(224, 226, 232),
            translationTextColor = rgb(235, 244, 255),
            attributionTextColor = rgb(166, 171, 183),
            labelColor = argb(235, 23, 23, 26),
            labelStrokeColor = accent,
            labelTextColor = rgb(241, 247, 255),
            selectionFillColor = withAlpha(accent, 30),
            instructionColor = argb(215, 23, 23, 26),
            panelCornerDp = 24,
            labelCornerDp = 12,
            controlCornerDp = 16,
        )

        UiStyle.MATERIAL3 -> OverlayVisualStyle(
            panelColor = argb(244, 29, 27, 32),
            panelStrokeColor = withAlpha(accent, 170),
            accentColor = accent,
            statusTextColor = accent,
            originalTextColor = rgb(231, 225, 229),
            translationTextColor = rgb(255, 255, 255),
            attributionTextColor = rgb(202, 196, 208),
            labelColor = argb(232, 29, 27, 32),
            labelStrokeColor = accent,
            labelTextColor = rgb(255, 255, 255),
            selectionFillColor = withAlpha(accent, 30),
            instructionColor = argb(215, 29, 27, 32),
            panelCornerDp = 20,
            labelCornerDp = 12,
            controlCornerDp = 20,
        )
    }
}

internal fun resolveOverlayVisualStyle(context: Context): OverlayVisualStyle {
    val preferences = AppPreferences(context)
    val dynamicAccent = if (
        preferences.uiStyle == UiStyle.MATERIAL3 && preferences.materialMonetEnabled
    ) {
        runCatching { context.getColor(android.R.color.system_accent1_100) }.getOrNull()
    } else {
        null
    }
    return overlayVisualStyle(preferences.uiStyle, dynamicAccent)
}

private fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    ((alpha and 0xFF) shl 24) or
        ((red and 0xFF) shl 16) or
        ((green and 0xFF) shl 8) or
        (blue and 0xFF)

private fun withAlpha(color: Int, alpha: Int): Int =
    ((alpha and 0xFF) shl 24) or (color and 0x00FFFFFF)
