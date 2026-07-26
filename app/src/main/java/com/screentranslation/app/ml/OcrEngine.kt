package com.screentranslation.app.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bundled on-device ML Kit OCR selected by the configured source language.
 */
class OcrEngine(sourceLanguage: String) : AutoCloseable {
    /**
     * @property text the whole region as ML Kit joins it, used for change detection
     * @property blocks the same content split by [Text.TextBlock], used for translation
     *
     * The split matters: translating the joined string lets unrelated UI lines
     * contaminate each other's context.
     */
    data class Recognition(
        val text: String,
        val blocks: List<String>,
    )

    private val closed = AtomicBoolean(false)
    private val recognizer: TextRecognizer = createRecognizer(sourceLanguage)

    fun recognize(bitmap: Bitmap, onResult: (Result<Recognition>) -> Unit) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("OCR engine is closed")))
            return
        }

        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer
            .process(input)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(Result.success(task.result.toRecognition()))
                } else {
                    onResult(
                        Result.failure(
                            task.exception ?: IllegalStateException("OCR failed without an exception"),
                        ),
                    )
                }
            }
    }

    private fun Text?.toRecognition(): Recognition {
        if (this == null) return Recognition("", emptyList())
        return Recognition(
            text = text,
            blocks = textBlocks.map { it.text.trim() }.filter { it.isNotEmpty() },
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            recognizer.close()
        }
    }

    private fun createRecognizer(languageTag: String): TextRecognizer {
        val language = languageTag
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore('-')
            .substringBefore('_')

        return when (language) {
            "zh" -> TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )

            "ja" -> TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build(),
            )

            "ko" -> TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build(),
            )

            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
}
