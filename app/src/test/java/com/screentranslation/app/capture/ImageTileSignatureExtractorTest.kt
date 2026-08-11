package com.screentranslation.app.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTileSignatureExtractorTest {
    @Test
    fun `samples RGBA centers without reading padded row bytes`() {
        val width = 12
        val height = 24
        val rowStride = width * 4 + 8
        val plane = rgbaPlane(width, height, rowStride) { x, y ->
            intArrayOf(x * 10, y * 5, 20, 255)
        }

        val frame = ImageTileSignatureExtractor.extractRgbaPlane(
            buffer = plane,
            imageWidth = width,
            imageHeight = height,
            rowStride = rowStride,
            pixelStride = 4,
        )

        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        assertEquals(18, frame.tiles.size)
        assertTrue(frame.signatures.all { it.size == 1 })
        assertEquals(expectedLuminance(red = 20, green = 10, blue = 20), unsigned(frame, 0))
        assertEquals(expectedLuminance(red = 100, green = 110, blue = 20), unsigned(frame, 17))
        assertEquals(width.toLong() * height * 4L, frame.avoidedBitmapBytes)
    }

    @Test
    fun `overlay exclusions are represented as white before dirty comparison`() {
        val width = 12
        val height = 24
        val rowStride = width * 4
        val plane = rgbaPlane(width, height, rowStride) { _, _ ->
            intArrayOf(10, 20, 30, 255)
        }

        val frame = ImageTileSignatureExtractor.extractRgbaPlane(
            buffer = plane,
            imageWidth = width,
            imageHeight = height,
            rowStride = rowStride,
            pixelStride = 4,
            exclusions = listOf(SignaturePixelRegion(0, 0, 4, 4)),
        )

        assertEquals(255, unsigned(frame, 0))
        assertEquals(expectedLuminance(10, 20, 30), unsigned(frame, 1))
    }

    @Test
    fun `a four pixel subtitle-scale mutation changes only its owning tile`() {
        val width = 12
        val height = 24
        val rowStride = width * 4
        val baselinePlane = rgbaPlane(width, height, rowStride) { _, _ ->
            intArrayOf(240, 240, 240, 255)
        }
        val changedPlane = rgbaPlane(width, height, rowStride) { x, y ->
            if (x in 8 until 12 && y in 20 until 24) {
                intArrayOf(0, 0, 0, 255)
            } else {
                intArrayOf(240, 240, 240, 255)
            }
        }
        val baseline = ImageTileSignatureExtractor.extractRgbaPlane(
            baselinePlane,
            width,
            height,
            rowStride,
            4,
        )
        val changed = ImageTileSignatureExtractor.extractRgbaPlane(
            changedPlane,
            width,
            height,
            rowStride,
            4,
        )

        val result = TileSignatureDiffer().run {
            compare(baseline.signatures)
            compare(changed.signatures)
        }
        assertEquals(setOf(17), result.natural)
    }

    @Test
    fun `truncated hardware plane is rejected before an absolute read`() {
        val width = 12
        val height = 24
        val rowStride = width * 4
        assertThrows(IllegalArgumentException::class.java) {
            ImageTileSignatureExtractor.extractRgbaPlane(
                // Missing only the final alpha byte used by the bulk row copy.
                // RGB component bounds alone would not catch this truncation.
                buffer = ByteBuffer.allocate(rowStride * height - 1),
                imageWidth = width,
                imageHeight = height,
                rowStride = rowStride,
                pixelStride = 4,
            )
        }
    }

    private fun rgbaPlane(
        width: Int,
        height: Int,
        rowStride: Int,
        pixel: (x: Int, y: Int) -> IntArray,
    ): ByteBuffer = ByteBuffer.allocate(rowStride * height).also { buffer ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgba = pixel(x, y)
                val offset = y * rowStride + x * 4
                rgba.forEachIndexed { component, value ->
                    buffer.put(offset + component, value.toByte())
                }
            }
            for (padding in width * 4 until rowStride) {
                buffer.put(y * rowStride + padding, 0x7F)
            }
        }
        buffer.position(0)
    }

    private fun unsigned(frame: ImageTileSignatureFrame, tile: Int): Int =
        frame.signatures[tile].single().toInt() and 0xFF

    private fun expectedLuminance(red: Int, green: Int, blue: Int): Int =
        (red * 77 + green * 150 + blue * 29) ushr 8
}
