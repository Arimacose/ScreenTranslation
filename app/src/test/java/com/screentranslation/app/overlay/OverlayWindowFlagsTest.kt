package com.screentranslation.app.overlay

import android.view.WindowManager
import com.screentranslation.app.selectionGestureGuardWindowFlags
import com.screentranslation.app.selectionGestureGuardSystemUiFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowFlagsTest {
    @Test
    fun `region actions use a visible three plus two grid`() {
        val actions = listOf("expand", "freeze", "preset", "reselect", "stop")

        assertEquals(
            listOf(
                listOf("expand", "freeze", "preset"),
                listOf("reselect", "stop"),
            ),
            regionActionRows(actions),
        )
        assertTrue(regionActionRows(actions).all { it.size <= 3 })
    }


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

    @Test
    fun `full screen translations pass touches through and stay out of projection frames`() {
        val flags = fullScreenTranslationWindowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun `full screen control remains touchable but stays out of projection frames`() {
        val flags = fullScreenControlWindowFlags()

        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun `selection window owns focus so edge back is not sent to target app`() {
        val flags = selectionOverlayWindowFlags()

        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Test
    fun `gesture guard owns back focus but passes selection touches through`() {
        val flags = selectionGestureGuardWindowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `gesture guard enters immersive mode for full edge exclusion`() {
        val flags = selectionGestureGuardSystemUiFlags()

        assertTrue(flags and android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        assertTrue(flags and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(flags and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
    }

    @Test
    fun `small subtitle selection threshold is thirty two dp`() {
        assertEquals(32f, MIN_SELECTION_SIZE_DP)
        assertTrue(isSelectionSizeAccepted(80, 80, 80f))
        assertEquals(false, isSelectionSizeAccepted(79, 80, 80f))
    }

    @Test
    fun `region content keeps one successful pair through pending and failure`() {
        val firstSuccess = RegionOverlayContentPolicy.success(
            original = "old source",
            translation = "旧译文",
        )
        val pending = RegionOverlayContentPolicy.pending(
            currentOriginal = firstSuccess.original,
            currentTranslation = firstSuccess.translation,
            nextOriginal = "new source",
        )
        val failed = RegionOverlayContentPolicy.failure(
            currentOriginal = pending.original,
            currentTranslation = pending.translation,
        )

        assertEquals(firstSuccess, pending)
        assertEquals(firstSuccess, failed)

        val nextSuccess = RegionOverlayContentPolicy.success(
            original = "new source",
            translation = "新译文",
        )
        assertEquals("new source", nextSuccess.original)
        assertEquals("新译文", nextSuccess.translation)
    }
}
