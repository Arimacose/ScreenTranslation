package com.screentranslation.app.prefs

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPresetStoreTest {
    @Test
    fun `invalid and out of bounds coordinates are finite clamped and usable`() {
        val result = clampNormalizedRegion(
            left = Float.NaN,
            top = 1.4f,
            right = -0.8f,
            bottom = Float.POSITIVE_INFINITY,
        )

        assertTrue(result.left in 0f..1f)
        assertTrue(result.top in 0f..1f)
        assertTrue(result.right in 0f..1f)
        assertTrue(result.bottom in 0f..1f)
        assertTrue(result.right - result.left >= 0.0299f)
        assertTrue(result.bottom - result.top >= 0.0299f)
    }

    @Test
    fun `normalized coordinates restore proportionally at another resolution`() {
        val preset = clampNormalizedRegion(0.10f, 0.25f, 0.90f, 0.75f)

        assertEquals(144, (preset.left * 1_440).toInt())
        assertEquals(800, (preset.top * 3_200).toInt())
        assertEquals(2_880, (preset.right * 3_200).toInt())
        assertEquals(1_080, (preset.bottom * 1_440).toInt())
    }

    @Test
    fun `orientation mapping chooses independent portrait and landscape slots`() {
        assertEquals(
            RegionPresetOrientation.PORTRAIT,
            RegionPresetOrientation.fromOrientationValue(Configuration.ORIENTATION_PORTRAIT),
        )
        assertEquals(
            RegionPresetOrientation.LANDSCAPE,
            RegionPresetOrientation.fromOrientationValue(Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            RegionPresetOrientation.PORTRAIT,
            RegionPresetOrientation.fromOrientationValue(Configuration.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun `starter names cover subtitles and dialog without content history`() {
        assertEquals("底部字幕", RegionPresetStore.STARTER_BOTTOM_SUBTITLES)
        assertEquals("中心对话", RegionPresetStore.STARTER_CENTER_DIALOG)
        val fields = RegionPresetEntry::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("screenshot" !in fields)
        assertTrue("ocr" !in fields)
        assertTrue("translation" !in fields)
    }
}
