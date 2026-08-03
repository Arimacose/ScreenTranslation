package com.screentranslation.app.ml

import android.graphics.Bitmap

/**
 * OCR boundary shared by the production PP-OCRv6 engine and benchmark
 * baselines. Implementations own their worker threads and native sessions.
 */
interface OcrEngine : AutoCloseable {
    fun recognize(bitmap: Bitmap, onResult: (Result<Recognition>) -> Unit)

    /**
     * @property text complete normalized reading-order text for change detection
     * @property blocks paragraph-sized units retained for translation
     * @property regions recognized line geometry normalized to the input bitmap
     */
    data class Recognition(
        val text: String,
        val blocks: List<String>,
        val regions: List<TextRegion> = emptyList(),
    )

    data class TextRegion(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )
}
