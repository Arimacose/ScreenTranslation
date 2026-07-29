package com.screentranslation.app.ml

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class PpOcrv6PostProcessorTest {
    @Test
    fun `ctc decode removes blanks and repeated classes`() {
        val rows = listOf(
            floatArrayOf(0.1f, 0.8f, 0.05f, 0.05f),
            floatArrayOf(0.1f, 0.7f, 0.1f, 0.1f),
            floatArrayOf(0.9f, 0.05f, 0.025f, 0.025f),
            floatArrayOf(0.1f, 0.1f, 0.75f, 0.05f),
            floatArrayOf(0.05f, 0.05f, 0.1f, 0.8f),
            floatArrayOf(0.05f, 0.05f, 0.1f, 0.8f),
        )
        val decoded = PpOcrv6CtcDecoder.decode(
            probabilities = FloatBuffer.wrap(rows.flatMap { it.asList() }.toFloatArray()),
            batchIndex = 0,
            timeSteps = rows.size,
            classCount = 4,
            characters = listOf("A", "B"),
        )

        assertEquals("AB ", decoded.text)
        assertEquals((0.8f + 0.75f + 0.8f) / 3f, decoded.confidence, 0.0001f)
    }

    @Test
    fun `connected components on the same row merge into one text line`() {
        val probabilities = probabilityMap(
            width = 20,
            height = 10,
            regions = listOf(
                Region(2, 2, 5, 6, 0.9f),
                Region(8, 2, 12, 6, 0.8f),
            ),
        )

        val boxes = PpOcrv6PostProcessor.extractBoxes(
            probabilities = probabilities,
            mapWidth = 20,
            mapHeight = 10,
            sourceWidth = 200,
            sourceHeight = 100,
            minimumPixels = 1,
        )

        assertEquals(1, boxes.size)
        assertEquals(true, boxes.single().top in 0..20)
        assertEquals(true, boxes.single().bottom in 70..100)
        assertEquals(true, boxes.single().left <= 20)
        assertEquals(true, boxes.single().right >= 120)
    }

    @Test
    fun `separate rows remain ordered blocks`() {
        val probabilities = probabilityMap(
            width = 12,
            height = 12,
            regions = listOf(
                Region(2, 7, 8, 10, 0.9f),
                Region(1, 1, 7, 4, 0.9f),
            ),
        )

        val boxes = PpOcrv6PostProcessor.extractBoxes(
            probabilities = probabilities,
            mapWidth = 12,
            mapHeight = 12,
            sourceWidth = 120,
            sourceHeight = 120,
            minimumPixels = 1,
        )

        assertEquals(2, boxes.size)
        assertEquals(true, boxes[0].top < boxes[1].top)
    }

    @Test
    fun `low confidence component is rejected`() {
        val probabilities = probabilityMap(
            width = 8,
            height = 8,
            regions = listOf(Region(2, 2, 6, 6, 0.3f)),
        )

        val boxes = PpOcrv6PostProcessor.extractBoxes(
            probabilities = probabilities,
            mapWidth = 8,
            mapHeight = 8,
            sourceWidth = 80,
            sourceHeight = 80,
            minimumPixels = 1,
        )

        assertEquals(emptyList<PpOcrBox>(), boxes)
    }

    private fun probabilityMap(
        width: Int,
        height: Int,
        regions: List<Region>,
    ): FloatArray = FloatArray(width * height).also { values ->
        regions.forEach { region ->
            for (y in region.top until region.bottom) {
                for (x in region.left until region.right) {
                    values[(y * width) + x] = region.probability
                }
            }
        }
    }

    private data class Region(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val probability: Float,
    )
}
