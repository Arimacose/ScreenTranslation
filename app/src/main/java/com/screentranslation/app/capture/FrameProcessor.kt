package com.screentranslation.app.capture

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.ImageReader
import android.os.SystemClock
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.TranslationEngine
import com.screentranslation.app.util.StableTextGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Drains ImageReader frames, throttles work, and serializes the asynchronous
 * OCR -> stability -> translation pipeline.
 */
class FrameProcessor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine,
    private val stableTextGate: StableTextGate = StableTextGate(),
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onTranslation: (FrameTranslation) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    data class FrameTranslation(
        val originalText: String,
        val translatedText: String,
    )

    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    @Volatile
    private var enabled = true

    @Volatile
    private var lastAcceptedFrameAt = 0L

    init {
        require(frameIntervalMs >= 0L) {
            "frameIntervalMs cannot be negative"
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun resetStability() {
        generation.incrementAndGet()
        stableTextGate.reset()
    }

    /**
     * Always calls acquireLatestImage so the reader is drained even when work
     * is throttled or another ML task is in flight.
     */
    fun onImageAvailable(
        reader: ImageReader,
        normalizedRegion: RectF = FULL_FRAME,
        normalizedExclusion: RectF? = null,
    ) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: IllegalStateException) {
            if (!closed.get()) onError(error)
            return
        } ?: return

        if (closed.get() || !enabled || processing.get()) {
            image.close()
            return
        }

        val now = elapsedRealtime()
        val previousFrameAt = lastAcceptedFrameAt
        if (previousFrameAt != 0L && now - previousFrameAt < frameIntervalMs) {
            image.close()
            return
        }
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAcceptedFrameAt = now
        val frameGeneration = generation.get()

        val bitmap = try {
            image.use {
                BitmapExtractor.extract(
                    image = it,
                    normalizedRegion = normalizedRegion,
                    normalizedExclusion = normalizedExclusion,
                )
            }
        } catch (error: Throwable) {
            processing.set(false)
            onError(error)
            return
        }

        recognize(bitmap, frameGeneration)
    }

    private fun recognize(bitmap: Bitmap, frameGeneration: Long) {
        ocrEngine.recognize(bitmap) { recognition ->
            bitmap.recycleSafely()

            if (closed.get() || generation.get() != frameGeneration) {
                processing.set(false)
                return@recognize
            }

            recognition.fold(
                onSuccess = { recognizedText ->
                    val stableText = stableTextGate.offer(recognizedText)
                    if (stableText == null) {
                        processing.set(false)
                    } else {
                        translate(stableText, frameGeneration)
                    }
                },
                onFailure = { error ->
                    processing.set(false)
                    onError(error)
                },
            )
        }
    }

    private fun translate(originalText: String, frameGeneration: Long) {
        translationEngine.translate(originalText) { translation ->
            processing.set(false)
            if (closed.get() || generation.get() != frameGeneration) return@translate

            translation.fold(
                onSuccess = { translatedText ->
                    onTranslation(
                        FrameTranslation(
                            originalText = originalText,
                            translatedText = translatedText,
                        ),
                    )
                },
                onFailure = onError,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        enabled = false
        generation.incrementAndGet()
        stableTextGate.reset()
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 450L
        private val FULL_FRAME = RectF(0f, 0f, 1f, 1f)
    }
}
