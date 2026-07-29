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
import com.screentranslation.app.util.TextBlockMerger
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ML Kit baseline retained only by the benchmark variant so future OCR changes
 * can be scored against the v0.1.0 engine with identical fixtures.
 */
internal class MlKitOcrEngine(sourceLanguage: String) : OcrEngine {
    private val closed = AtomicBoolean(false)
    private val recognizer: TextRecognizer = createRecognizer(sourceLanguage)

    override fun recognize(
        bitmap: Bitmap,
        onResult: (Result<OcrEngine.Recognition>) -> Unit,
    ) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("OCR engine is closed")))
            return
        }

        recognizer
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(Result.success(task.result.toRecognition()))
                } else {
                    onResult(
                        Result.failure(
                            task.exception
                                ?: IllegalStateException("OCR failed without an exception"),
                        ),
                    )
                }
            }
    }

    private fun Text?.toRecognition(): OcrEngine.Recognition {
        if (this == null) return OcrEngine.Recognition("", emptyList())
        val raw = textBlocks.map { it.text.trim() }.filter { it.isNotEmpty() }
        return OcrEngine.Recognition(
            text = text,
            blocks = TextBlockMerger.merge(raw),
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) recognizer.close()
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
