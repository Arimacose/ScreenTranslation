package com.screentranslation.app.overlay

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowFlagsTest {

    @Test
    fun `translation panel is available to system screenshots`() {
        val flags = overlayWindowFlags()

        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `overlay keeps its required input and layout behavior`() {
        val flags = overlayWindowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
    }
}
