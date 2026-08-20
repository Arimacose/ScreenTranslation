package com.screentranslation.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrProfileTest {
    @Test
    fun `profile values are bounded and persisted ids round trip`() {
        OcrProfileId.entries.forEach { id ->
            val profile = OcrProfiles.forId(id)
            assertEquals(id, OcrProfileId.fromPersisted(id.persistedValue))
            assertTrue(profile.detectionLongSide in 320..1_600)
        }
        assertEquals(OcrProfileId.BALANCED, OcrProfileId.fromPersisted("unknown"))
    }

    @Test
    fun `balanced never schedules a second pass`() {
        assertFalse(
            OcrSecondPassPolicy.shouldRun(
                OcrRequest(OcrProfiles.BALANCED),
                OcrEngine.Recognition("", emptyList()),
                800,
                400,
            ),
        )
    }

    @Test
    fun `small subtitle second pass respects tile and pixel budgets`() {
        val request = OcrRequest(OcrProfiles.SMALL_SUBTITLE)
        val empty = OcrEngine.Recognition("", emptyList())

        assertTrue(OcrSecondPassPolicy.shouldRun(request, empty, 800, 400, 1))
        assertFalse(OcrSecondPassPolicy.shouldRun(request, empty, 2_000, 1_000, 1))
        assertFalse(OcrSecondPassPolicy.shouldRun(request, empty, 800, 400, 3))
        assertFalse(
            OcrSecondPassPolicy.shouldRun(
                request.copy(passIndex = 2),
                empty,
                800,
                400,
                1,
            ),
        )
    }

    @Test
    fun `second pass duplicate suppression retains the stronger region once`() {
        val first = recognition("tiny", 0.20f, 0.80f, 0.90f, 0.90f)
        val second = recognition("tiny text", 0.19f, 0.79f, 0.91f, 0.91f, confidence = 0.92f)

        val merged = OcrSecondPassPolicy.merge(first, second)

        assertEquals(1, merged.regions.size)
        assertEquals("tiny text", merged.regions.single().text)
    }

    @Test
    fun `non-overlapping second pass result is retained`() {
        val first = recognition("first", 0.1f, 0.1f, 0.4f, 0.2f)
        val second = recognition("second", 0.6f, 0.7f, 0.9f, 0.8f)

        assertEquals(2, OcrSecondPassPolicy.merge(first, second).regions.size)
    }

    private fun recognition(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.3f,
    ): OcrEngine.Recognition = OcrEngine.Recognition(
        text = text,
        blocks = listOf(text),
        regions = listOf(OcrEngine.TextRegion(text, left, top, right, bottom, confidence)),
    )
}
