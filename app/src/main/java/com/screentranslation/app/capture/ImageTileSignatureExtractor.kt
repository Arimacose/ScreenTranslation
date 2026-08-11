package com.screentranslation.app.capture

import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.Image
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal data class SignaturePixelRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

internal data class ImageTileSignatureFrame(
    val width: Int,
    val height: Int,
    val tiles: List<PixelTile>,
    val signatures: List<ByteArray>,
) {
    val avoidedBitmapBytes: Long get() = width.toLong() * height * RGBA_BYTES_PER_PIXEL

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4L
    }
}

/**
 * Builds low-resolution luminance signatures directly from an RGBA Image plane.
 *
 * A stable frame therefore never needs the ~17.6 MiB full-screen Bitmap used by
 * OCR on the 1440 x 3200 target display. One centre sample represents each 4 x 4
 * source-pixel cell; overlay rectangles are represented as white, matching the
 * masking semantics used by [BitmapExtractor] on frames that proceed to OCR.
 */
internal object ImageTileSignatureExtractor {
    fun extract(
        image: Image,
        normalizedRegion: RectF,
        normalizedExclusions: List<RectF>,
    ): ImageTileSignatureFrame {
        require(image.format == PixelFormat.RGBA_8888) {
            "Expected RGBA_8888, received image format ${image.format}"
        }
        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("The image has no pixel plane")
        return extractRgbaPlane(
            buffer = plane.buffer,
            imageWidth = image.width,
            imageHeight = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            crop = normalizedRegion.toPixelRegion(image.width, image.height),
            exclusions = normalizedExclusions.map {
                it.toPixelRegion(image.width, image.height)
            },
        )
    }

    internal fun extractRgbaPlane(
        buffer: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        crop: SignaturePixelRegion = SignaturePixelRegion(0, 0, imageWidth, imageHeight),
        exclusions: List<SignaturePixelRegion> = emptyList(),
    ): ImageTileSignatureFrame {
        require(imageWidth > 0 && imageHeight > 0)
        require(pixelStride >= RGBA_BYTES_PER_PIXEL) {
            "Unsupported RGBA pixel stride: $pixelStride"
        }
        require(rowStride >= imageWidth * pixelStride) {
            "Invalid row stride $rowStride for width $imageWidth"
        }
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= imageWidth &&
            crop.bottom <= imageHeight && crop.width > 0 && crop.height > 0
        ) {
            "Crop $crop is outside ${imageWidth}x$imageHeight"
        }

        val source = buffer.duplicate()
        val baseOffset = source.position()
        val rowBytesLong = crop.width.toLong() * pixelStride
        require(rowBytesLong <= Int.MAX_VALUE) {
            "Crop row is too large to materialize: $rowBytesLong bytes"
        }
        val lastRequiredByteExclusive = baseOffset.toLong() +
            (crop.bottom - 1L) * rowStride +
            crop.left.toLong() * pixelStride +
            rowBytesLong
        require(lastRequiredByteExclusive <= source.limit()) {
            "Crop $crop exceeds the ${source.limit()} byte pixel plane " +
                "(rowStride=$rowStride, pixelStride=$pixelStride)"
        }

        val width = crop.width
        val height = crop.height
        val signatureWidth = max(1, width / SIGNATURE_SCALE_DIVISOR)
        val signatureHeight = max(1, height / SIGNATURE_SCALE_DIVISOR)
        val tiles = ScreenTileGrid.create(width, height)

        fun sampledX(x: Int): Int = crop.left +
            (((x * 2L + 1L) * width) / (signatureWidth * 2L)).toInt()
                .coerceIn(0, width - 1)

        fun sampledY(y: Int): Int = crop.top +
            (((y * 2L + 1L) * height) / (signatureHeight * 2L)).toInt()
                .coerceIn(0, height - 1)

        val sampleXs = IntArray(signatureWidth, ::sampledX)
        val sampleYs = IntArray(signatureHeight, ::sampledY)
        val maskedSamples = BooleanArray(signatureWidth * signatureHeight)
        exclusions.forEach { exclusion ->
            val left = sampleXs.lowerBound(exclusion.left)
            val right = sampleXs.lowerBound(exclusion.right)
            val top = sampleYs.lowerBound(exclusion.top)
            val bottom = sampleYs.lowerBound(exclusion.bottom)
            if (left < right && top < bottom) {
                for (y in top until bottom) {
                    Arrays.fill(
                        maskedSamples,
                        y * signatureWidth + left,
                        y * signatureWidth + right,
                        true,
                    )
                }
            }
        }

        // Bulk-copy each sampled source row once. Absolute DirectByteBuffer#get
        // per RGB component is disproportionately expensive on ART; 800 row
        // copies plus ordinary ByteArray reads are substantially cheaper than
        // ~864,000 JNI-backed absolute reads for a 1440 x 3200 frame.
        val rowBytes = rowBytesLong.toInt()
        val row = ByteArray(rowBytes)
        val luminance = ByteArray(signatureWidth * signatureHeight)
        for (sampleY in 0 until signatureHeight) {
            val sourceY = sampleYs[sampleY]
            source.position(baseOffset + sourceY * rowStride + crop.left * pixelStride)
            source.get(row, 0, rowBytes)
            val outputRow = sampleY * signatureWidth
            for (sampleX in 0 until signatureWidth) {
                val output = outputRow + sampleX
                if (maskedSamples[output]) {
                    luminance[output] = WHITE_LUMINANCE.toByte()
                    continue
                }
                val offset = (sampleXs[sampleX] - crop.left) * pixelStride
                val red = row[offset].toInt() and 0xFF
                val green = row[offset + 1].toInt() and 0xFF
                val blue = row[offset + 2].toInt() and 0xFF
                luminance[output] = ((red * 77 + green * 150 + blue * 29) ushr 8).toByte()
            }
        }

        val signatures = tiles.map { tile ->
            val left = tile.left * signatureWidth / width
            val top = tile.top * signatureHeight / height
            val right = max(left + 1, tile.right * signatureWidth / width)
                .coerceAtMost(signatureWidth)
            val bottom = max(top + 1, tile.bottom * signatureHeight / height)
                .coerceAtMost(signatureHeight)
            ByteArray((right - left) * (bottom - top)).also { signature ->
                var output = 0
                for (y in top until bottom) {
                    val count = right - left
                    System.arraycopy(
                        luminance,
                        y * signatureWidth + left,
                        signature,
                        output,
                        count,
                    )
                    output += count
                }
            }
        }
        return ImageTileSignatureFrame(width, height, tiles, signatures)
    }

    private fun RectF.toPixelRegion(width: Int, height: Int): SignaturePixelRegion {
        val leftFraction = left.coerceIn(0f, 1f)
        val topFraction = top.coerceIn(0f, 1f)
        val rightFraction = right.coerceIn(leftFraction, 1f)
        val bottomFraction = bottom.coerceIn(topFraction, 1f)
        val leftPx = floor(leftFraction * width).toInt().coerceIn(0, width - 1)
        val topPx = floor(topFraction * height).toInt().coerceIn(0, height - 1)
        val rightPx = ceil(rightFraction * width).toInt().coerceIn(leftPx + 1, width)
        val bottomPx = ceil(bottomFraction * height).toInt().coerceIn(topPx + 1, height)
        return SignaturePixelRegion(leftPx, topPx, rightPx, bottomPx)
    }

    private fun IntArray.lowerBound(target: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
    private const val SIGNATURE_SCALE_DIVISOR = 4
    private const val WHITE_LUMINANCE = 255
}
