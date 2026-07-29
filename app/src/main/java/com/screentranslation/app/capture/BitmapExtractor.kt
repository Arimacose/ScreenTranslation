package com.screentranslation.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Copies the selected region of an [ImageReader][android.media.ImageReader]
 * RGBA frame into a tightly packed bitmap.
 *
 * Only the rows and columns covered by the crop rectangle are read out of the
 * hardware buffer. A full-screen intermediate is never materialized: at
 * 3200x1440 that intermediate alone is ~17.6 MB per frame, which at a 250 ms
 * interval is roughly 70 MB/s of allocation churn for a region that is usually
 * a small fraction of the display.
 *
 * The returned bitmap belongs to the caller. The caller still owns [image] and
 * must close it.
 */
object BitmapExtractor {

    fun extract(image: Image): Bitmap = extract(image, FULL_FRAME)

    fun extract(
        image: Image,
        normalizedRegion: RectF,
        normalizedExclusion: RectF? = null,
    ): Bitmap {
        val crop = normalizedRegion.toPixelRect(image.width, image.height)
        val bitmap = copyRegion(image, crop)

        normalizedExclusion?.let { exclusion ->
            val mask = exclusion.toPixelRect(image.width, image.height)
            maskOut(bitmap, crop, mask)
        }
        return bitmap
    }

    fun extract(image: Image, pixelRegion: Rect): Bitmap =
        copyRegion(image, pixelRegion.clampedTo(image.width, image.height))

    /**
     * Paints [mask] white so the translation overlay cannot be recognized by the
     * next OCR pass. [mask] is in full-frame coordinates and is translated into
     * the cropped bitmap's coordinate space; Canvas clips whatever falls outside.
     */
    private fun maskOut(bitmap: Bitmap, crop: Rect, mask: Rect) {
        if (!Rect.intersects(crop, mask)) return
        val local = Rect(mask).apply { offset(-crop.left, -crop.top) }
        Canvas(bitmap).drawRect(local, EXCLUSION_PAINT)
    }

    private fun copyRegion(image: Image, crop: Rect): Bitmap {
        require(image.format == PixelFormat.RGBA_8888) {
            "Expected RGBA_8888, received image format ${image.format}"
        }

        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("The image has no pixel plane")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        require(pixelStride == RGBA_BYTES_PER_PIXEL) {
            "Unsupported RGBA pixel stride: $pixelStride"
        }
        require(rowStride >= image.width * pixelStride) {
            "Invalid row stride $rowStride for width ${image.width}"
        }

        val width = crop.width()
        val height = crop.height()
        val rowBytes = width * pixelStride

        val source = plane.buffer.duplicate()
        val lastByte = (crop.bottom - 1) * rowStride + crop.right * pixelStride
        require(lastByte <= source.limit()) {
            "Crop $crop exceeds the ${source.limit()} byte pixel plane " +
                "(rowStride=$rowStride, image=${image.width}x${image.height})"
        }

        // Row-by-row so the padding gralloc leaves at the end of each row, and
        // every column outside the crop, is skipped rather than copied twice.
        val destination = ByteBuffer.allocateDirect(rowBytes * height)
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            source.position((crop.top + y) * rowStride + crop.left * pixelStride)
            source.get(row, 0, rowBytes)
            destination.put(row)
        }
        destination.rewind()

        val bitmap = createBitmap(width, height)
        return try {
            bitmap.copyPixelsFromBuffer(destination)
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun RectF.toPixelRect(width: Int, height: Int): Rect {
        val leftFraction = left.coerceIn(0f, 1f)
        val topFraction = top.coerceIn(0f, 1f)
        val rightFraction = right.coerceIn(leftFraction, 1f)
        val bottomFraction = bottom.coerceIn(topFraction, 1f)

        val leftPx = floor(leftFraction * width).toInt().coerceIn(0, width - 1)
        val topPx = floor(topFraction * height).toInt().coerceIn(0, height - 1)
        val rightPx = ceil(rightFraction * width).toInt().coerceIn(leftPx + 1, width)
        val bottomPx = ceil(bottomFraction * height).toInt().coerceIn(topPx + 1, height)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    private fun Rect.clampedTo(width: Int, height: Int): Rect {
        val leftPx = left.coerceIn(0, width - 1)
        val topPx = top.coerceIn(0, height - 1)
        val rightPx = right.coerceIn(leftPx + 1, width)
        val bottomPx = bottom.coerceIn(topPx + 1, height)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
    private val FULL_FRAME = RectF(0f, 0f, 1f, 1f)
    private val EXCLUSION_PAINT = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
}
