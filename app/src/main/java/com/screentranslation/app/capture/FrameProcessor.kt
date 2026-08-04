package com.screentranslation.app.capture

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.ImageReader
import android.os.SystemClock
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationInputMode
import com.screentranslation.app.util.ClauseSplitter
import com.screentranslation.app.util.ProtectedTextCodec
import com.screentranslation.app.util.SourceTextFilter
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
    private val sourceLanguageTag: String,
    private val targetLanguageTag: String,
    private val stableTextGate: StableTextGate = StableTextGate(),
    frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onOriginalRecognized: (String) -> Unit = {},
    private val onTranslation: (FrameTranslation) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : FramePipeline {
    data class FrameTranslation(
        val originalText: String,
        val translatedText: String,
    )

    private val gate = FrameGate(frameIntervalMs, elapsedRealtime)
    private var hadAcceptedSource = false
    private val wholeRegionCoordinator =
        if (translationEngine.inputMode == TranslationInputMode.WHOLE_REGION) {
            TranslationCoordinator(
                backend = translationEngine,
                onTranslation = { original, translated ->
                    val protected = synchronized(wholeRegionProtectedText) {
                        wholeRegionProtectedText.remove(original)
                    }
                    onTranslation(
                        FrameTranslation(
                            originalText = protected?.original ?: original,
                            translatedText = protected?.restore(translated) ?: translated,
                        ),
                    )
                },
                onError = onError,
            )
        } else {
            null
        }

    private val wholeRegionProtectedText = object : LinkedHashMap<
        String,
        ProtectedTextCodec.ProtectedText,
    >(WHOLE_REGION_PROTECTION_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ProtectedTextCodec.ProtectedText>,
        ): Boolean = size > WHOLE_REGION_PROTECTION_ENTRIES
    }

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

    override fun setEnabled(value: Boolean) {
        gate.setEnabled(value)
        if (!value) {
            wholeRegionCoordinator?.reset()
            stableTextGate.reset()
            synchronized(wholeRegionProtectedText) { wholeRegionProtectedText.clear() }
            hadAcceptedSource = false
        }
    }

    override fun resetStability() {
        gate.invalidate()
        stableTextGate.reset()
        wholeRegionCoordinator?.reset()
        synchronized(wholeRegionProtectedText) { wholeRegionProtectedText.clear() }
        hadAcceptedSource = false
    }

    /**
     * Always calls acquireLatestImage so the reader is drained even when work
     * is throttled or another ML task is in flight.
     */
    override fun onImageAvailable(
        reader: ImageReader,
        normalizedRegion: RectF,
        normalizedExclusions: List<RectF>,
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
                    normalizedExclusions = normalizedExclusions,
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
                    val recognitionBlocks = recognition.blocks.ifEmpty {
                        recognition.regions.map { it.text }.ifEmpty {
                            recognition.text.lineSequence().toList()
                        }
                    }
                    val filteredBlocks = SourceTextFilter.filterBlocks(
                        blocks = recognitionBlocks,
                        sourceLanguageTag = sourceLanguageTag,
                        targetLanguageTag = targetLanguageTag,
                    )
                    val filteredText = filteredBlocks.joinToString("\n")
                    if (filteredText.isBlank()) {
                        clearWhenNoSourceText()
                        gate.release()
                        return@fold
                    }

                    // Stability is judged on the whole region: a partially
                    // repainted UI should not be treated as settled just because
                    // one block happens to match.
                    val stableText = stableTextGate.offer(filteredText)
                    if (stableText == null) {
                        gate.release()
                    } else if (
                        translationEngine.inputMode == TranslationInputMode.WHOLE_REGION
                    ) {
                        // Online requests must not hold the capture single-flight
                        // slot: newer stable OCR is allowed to replace an older
                        // in-flight request through the coordinator.
                        gate.release()
                        hadAcceptedSource = true
                        onOriginalRecognized(stableText)
                        val protected = ProtectedTextCodec.protect(stableText)
                        synchronized(wholeRegionProtectedText) {
                            wholeRegionProtectedText[protected.encoded] = protected
                        }
                        checkNotNull(wholeRegionCoordinator).submit(protected.encoded)
                    } else {
                        hadAcceptedSource = true
                        translate(stableText, filteredBlocks, frameGeneration)
                    }
                },
                onFailure = { error ->
                    gate.release()
                    onError(error)
                },
            )
        }
    }

    private fun clearWhenNoSourceText() {
        stableTextGate.reset()
        wholeRegionCoordinator?.reset()
        synchronized(wholeRegionProtectedText) { wholeRegionProtectedText.clear() }
        if (!hadAcceptedSource) return

        hadAcceptedSource = false
        onTranslation(FrameTranslation(originalText = "", translatedText = ""))
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

            val protected = ProtectedTextCodec.protect(part)
            translationEngine.translate(protected.encoded) { translation ->
                translation.fold(
                    onSuccess = { text ->
                        val restored = protected.restore(text)
                        translated[index] = restored
                        cache(part, restored)
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
        wholeRegionCoordinator?.close()
        stableTextGate.reset()
        synchronized(translationCache) { translationCache.clear() }
        synchronized(wholeRegionProtectedText) { wholeRegionProtectedText.clear() }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 450L
        private const val TRANSLATION_CACHE_ENTRIES = 128
        private const val WHOLE_REGION_PROTECTION_ENTRIES = 8
    }
}
