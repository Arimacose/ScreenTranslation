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
 * Copies an [ImageReader][android.media.ImageReader] RGBA frame into a tightly
 * packed bitmap and optionally crops it to the selected screen region.
 *
 * The returned bitmap belongs to the caller. The caller still owns [image] and
 * must close it.
 */
object BitmapExtractor {

    fun extract(image: Image): Bitmap = extractFullFrame(image)

    fun extract(
        image: Image,
        normalizedRegion: RectF,
        normalizedExclusion: RectF? = null,
    ): Bitmap {
        val fullFrame = extractFullFrame(image)
        val crop = normalizedRegion.toPixelRect(fullFrame.width, fullFrame.height)
        normalizedExclusion?.let { exclusion ->
            val mask = exclusion.toPixelRect(fullFrame.width, fullFrame.height)
            if (Rect.intersects(crop, mask)) {
                Canvas(fullFrame).drawRect(mask, EXCLUSION_PAINT)
            }
        }
        return cropAndRecycleSource(fullFrame, crop)
    }

    fun extract(image: Image, pixelRegion: Rect): Bitmap {
        val fullFrame = extractFullFrame(image)
        val crop = pixelRegion.clampedTo(fullFrame.width, fullFrame.height)
        return cropAndRecycleSource(fullFrame, crop)
    }

    private fun extractFullFrame(image: Image): Bitmap {
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

        val paddedWidth = rowStride / pixelStride
        val paddedBitmap = createBitmap(paddedWidth, image.height)
        val pixels = plane.buffer.duplicate().apply { rewind() }
        val bitmapBuffer = pixels.padTo(paddedBitmap.byteCount)

        try {
            paddedBitmap.copyPixelsFromBuffer(bitmapBuffer)
        } catch (error: Throwable) {
            paddedBitmap.recycle()
            throw error
        }

        if (paddedWidth == image.width) {
            return paddedBitmap
        }

        return try {
            Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
        } finally {
            paddedBitmap.recycle()
        }
    }

    private fun cropAndRecycleSource(source: Bitmap, crop: Rect): Bitmap {
        if (crop.left == 0 &&
            crop.top == 0 &&
            crop.right == source.width &&
            crop.bottom == source.height
        ) {
            return source
        }

        return try {
            Bitmap.createBitmap(
                source,
                crop.left,
                crop.top,
                crop.width(),
                crop.height(),
            )
        } finally {
            source.recycle()
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

    /**
     * Some gralloc implementations omit the unused padding bytes after the
     * final row from Buffer.limit(). Bitmap expects a full padded rectangle.
     */
    private fun ByteBuffer.padTo(requiredBytes: Int): ByteBuffer {
        if (remaining() >= requiredBytes) return this
        return ByteBuffer.allocateDirect(requiredBytes).apply {
            put(this@padTo)
            rewind()
        }
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
    private val EXCLUSION_PAINT = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
}
