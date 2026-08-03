package com.screentranslation.app.overlay

import com.screentranslation.app.model.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayVisualStyleTest {
    @Test
    fun `each UI language has a distinct overlay shape and accent`() {
        val apple = overlayVisualStyle(UiStyle.APPLE)
        val miuix = overlayVisualStyle(UiStyle.MIUIX)
        val material = overlayVisualStyle(UiStyle.MATERIAL3)

        assertNotEquals(apple.panelCornerDp, miuix.panelCornerDp)
        assertNotEquals(apple.accentColor, miuix.accentColor)
        assertNotEquals(miuix.controlCornerDp, material.controlCornerDp)
    }

    @Test
    fun `Material Monet accent is carried into overlay borders and status`() {
        val monetAccent = 0xFF7BD2B4.toInt()
        val material = overlayVisualStyle(UiStyle.MATERIAL3, monetAccent)

        assertEquals(monetAccent, material.accentColor)
        assertEquals(monetAccent, material.statusTextColor)
        assertEquals(monetAccent, material.labelStrokeColor)
    }

    @Test
    fun `fixed overlay palettes keep readable text contrast`() {
        UiStyle.entries.forEach { uiStyle ->
            val style = overlayVisualStyle(uiStyle)
            listOf(
                style.statusTextColor,
                style.originalTextColor,
                style.translationTextColor,
                style.attributionTextColor,
            ).forEach { textColor ->
                assertTrue(
                    "$uiStyle contrast was ${contrastRatio(textColor, style.panelColor)}",
                    contrastRatio(textColor, style.panelColor) >= 4.5,
                )
            }
            assertTrue(
                contrastRatio(style.labelTextColor, style.labelColor) >= 4.5,
            )
        }
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) {
                value / 12.92
            } else {
                Math.pow((value + 0.055) / 1.055, 2.4)
            }
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
