package com.screentranslation.app.capture

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.ImageReader
import android.os.SystemClock
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.util.ClauseSplitter
import com.screentranslation.app.util.StableTextGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drains ImageReader frames, throttles work, and serializes the asynchronous
 * OCR -> stability -> translation pipeline.
 *
 * Admission control lives in [FrameGate] so it can be unit tested without
 * Android types.
 */
class FrameProcessor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationBackend,
    private val stableTextGate: StableTextGate = StableTextGate(),
    frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onTranslation: (FrameTranslation) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    data class FrameTranslation(
        val originalText: String,
        val translatedText: String,
    )

    private val gate = FrameGate(frameIntervalMs, elapsedRealtime)

    /**
     * Most of a UI is unchanged between frames, so the same block text is
     * retranslated over and over without this. Access is synchronized because
     * OCR and translation engines complete on their own worker threads.
     */
    private val translationCache = object : LinkedHashMap<String, String>(
        TRANSLATION_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > TRANSLATION_CACHE_ENTRIES
    }

    fun setEnabled(value: Boolean) {
        gate.setEnabled(value)
    }

    fun resetStability() {
        gate.invalidate()
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
            if (!gate.isClosed) onError(error)
            return
        } ?: return

        val frameGeneration = gate.tryAcquire()
        if (frameGeneration == null) {
            image.close()
            return
        }

        val bitmap = try {
            image.use {
                BitmapExtractor.extract(
                    image = it,
                    normalizedRegion = normalizedRegion,
                    normalizedExclusion = normalizedExclusion,
                )
            }
        } catch (error: Throwable) {
            gate.release()
            onError(error)
            return
        }

        recognize(bitmap, frameGeneration)
    }

    private fun recognize(bitmap: Bitmap, frameGeneration: Long) {
        ocrEngine.recognize(bitmap) { result ->
            bitmap.recycleSafely()

            if (!gate.isCurrent(frameGeneration)) {
                gate.release()
                return@recognize
            }

            result.fold(
                onSuccess = { recognition ->
                    // Stability is judged on the whole region: a partially
                    // repainted UI should not be treated as settled just because
                    // one block happens to match.
                    val stableText = stableTextGate.offer(recognition.text)
                    if (stableText == null) {
                        gate.release()
                    } else {
                        translate(stableText, recognition.blocks, frameGeneration)
                    }
                },
                onFailure = { error ->
                    gate.release()
                    onError(error)
                },
            )
        }
    }

    /**
     * Translates each OCR block separately, then rejoins them.
     *
     * OCR hands back the region as one newline-joined string. Translating that
     * as a unit lets unrelated UI lines contaminate each other's context, and
     * on-device translation quality degrades noticeably on long inputs.
     */
    private fun translate(
        originalText: String,
        blocks: List<String>,
        frameGeneration: Long,
    ) {
        // Two levels, kept apart on purpose: blocks are separate pieces of UI and
        // stay on their own lines, while the clauses a block was split into are
        // one sentence and must be rejoined inline.
        val translationPlan = ClauseSplitter.plan(
            blocks.ifEmpty { listOf(originalText) },
        )
        val parts = translationPlan.parts
        val translated = arrayOfNulls<String>(parts.size)
        val remaining = AtomicInteger(parts.size)

        // Guarantees exactly one terminal action even if several blocks fail
        // concurrently, so the single-flight slot is released exactly once.
        val settled = AtomicBoolean(false)
        fun settle(action: () -> Unit) {
            if (settled.compareAndSet(false, true)) {
                gate.release()
                action()
            }
        }

        parts.forEachIndexed { index, part ->
            cached(part)?.let { hit ->
                translated[index] = hit
                if (remaining.decrementAndGet() == 0) {
                    settle { publish(originalText, translationPlan, translated, frameGeneration) }
                }
                return@forEachIndexed
            }

            translationEngine.translate(part) { translation ->
                translation.fold(
                    onSuccess = { text ->
                        translated[index] = text
                        cache(part, text)
                        if (remaining.decrementAndGet() == 0) {
                            settle { publish(originalText, translationPlan, translated, frameGeneration) }
                        }
                    },
                    onFailure = { error ->
                        settle {
                            if (gate.isCurrent(frameGeneration)) onError(error)
                        }
                    },
                )
            }
        }
    }

    private fun publish(
        originalText: String,
        translationPlan: ClauseSplitter.Plan,
        translated: Array<String?>,
        frameGeneration: Long,
    ) {
        if (!gate.isCurrent(frameGeneration)) return
        onTranslation(
            FrameTranslation(
                originalText = originalText,
                translatedText = translationPlan.reassemble(
                    translated.map { it.orEmpty() },
                ),
            ),
        )
    }

    private fun cached(text: String): String? =
        synchronized(translationCache) { translationCache[text] }

    private fun cache(text: String, translation: String) {
        synchronized(translationCache) { translationCache[text] = translation }
    }

    override fun close() {
        if (gate.isClosed) return
        gate.close()
        stableTextGate.reset()
        synchronized(translationCache) { translationCache.clear() }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 450L
        private const val TRANSLATION_CACHE_ENTRIES = 128
        private val FULL_FRAME = RectF(0f, 0f, 1f, 1f)
    }
}
