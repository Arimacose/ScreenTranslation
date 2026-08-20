package com.screentranslation.app.capture

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.ImageReader
import android.os.SystemClock
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.OcrProfile
import com.screentranslation.app.ml.OcrProfiles
import com.screentranslation.app.ml.OcrRequest
import com.screentranslation.app.ml.OcrSecondPassPolicy
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationInputMode
import com.screentranslation.app.util.ClauseSplitter
import com.screentranslation.app.util.OcrPunctuationRestorer
import com.screentranslation.app.util.SegmentedTextPlan
import com.screentranslation.app.util.SegmentedTextPlanner
import com.screentranslation.app.util.SourceTextFilter
import com.screentranslation.app.util.StableTextGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

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
    private val ocrProfile: OcrProfile = OcrProfiles.BALANCED,
    private val stableTextGate: StableTextGate = StableTextGate(),
    frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    private val onOriginalRecognized: (String) -> Unit = {},
    private val onTranslation: (FrameTranslation) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val performanceTelemetry: CapturePerformanceTelemetry? = null,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
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
                    val plan = synchronized(wholeRegionPlans) {
                        wholeRegionPlans.remove(original)
                    }
                    runCatching { plan?.restore(translated) ?: translated }.fold(
                        onSuccess = { restored ->
                            onTranslation(
                                FrameTranslation(
                                    originalText = plan?.originalText ?: original,
                                    translatedText = restored,
                                ),
                            )
                            performanceTelemetry?.recordTranslationPublished()
                        },
                        onFailure = onError,
                    )
                },
                onError = onError,
                performanceTelemetry = performanceTelemetry,
            )
        } else {
            null
        }

    private val wholeRegionPlans = object : LinkedHashMap<
        String,
        SegmentedTextPlan,
    >(WHOLE_REGION_PROTECTION_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SegmentedTextPlan>,
        ): Boolean = size > WHOLE_REGION_PROTECTION_ENTRIES
    }
    private var enabled = true

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
        if (enabled == value) return
        enabled = value
        performanceTelemetry?.recordEnabled(value)
        gate.setEnabled(value)
        if (!value) {
            wholeRegionCoordinator?.reset()
            stableTextGate.reset()
            synchronized(wholeRegionPlans) { wholeRegionPlans.clear() }
            hadAcceptedSource = false
        }
    }

    override fun resetStability() {
        performanceTelemetry?.recordLifecycleReset()
        gate.invalidate()
        stableTextGate.reset()
        wholeRegionCoordinator?.reset()
        synchronized(wholeRegionPlans) { wholeRegionPlans.clear() }
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
        performanceTelemetry?.recordFrameAvailable()

        val frameGeneration = gate.tryAcquire()
        if (frameGeneration == null) {
            performanceTelemetry?.recordFrameRejected()
            image.close()
            return
        }
        performanceTelemetry?.recordFrameAdmitted()

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
        performanceTelemetry?.recordBitmapMaterialized(bitmap.allocationByteCount.toLong())

        recognize(bitmap, frameGeneration)
    }

    private fun recognize(bitmap: Bitmap, frameGeneration: Long) {
        val request = OcrRequest(ocrProfile, passIndex = 1, roiIdentity = "region")
        val timing = performanceTelemetry?.startOcr(
            CapturePerformanceTelemetry.OcrPath.REGION,
            bitmap.width.toLong() * bitmap.height,
        )
        ocrEngine.recognize(bitmap, request) { result ->
            timing?.let { performanceTelemetry?.finishOcr(it, result.isSuccess) }
            if (!gate.isCurrent(frameGeneration)) {
                bitmap.recycleSafely()
                gate.release()
                return@recognize
            }
            result.fold(
                onSuccess = { recognition ->
                    if (OcrSecondPassPolicy.shouldRun(
                            request,
                            recognition,
                            bitmap.width,
                            bitmap.height,
                        )
                    ) {
                        recognizeRegionSecondPass(bitmap, recognition, frameGeneration)
                    } else {
                        bitmap.recycleSafely()
                        processRecognition(recognition, frameGeneration)
                    }
                },
                onFailure = { error ->
                    bitmap.recycleSafely()
                    gate.release()
                    onError(error)
                },
            )
        }
    }

    private fun recognizeRegionSecondPass(
        bitmap: Bitmap,
        first: OcrEngine.Recognition,
        frameGeneration: Long,
    ) {
        val budget = checkNotNull(ocrProfile.secondPass)
        val secondBitmap = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * budget.upscaleFactor).roundToInt().coerceAtLeast(1),
            (bitmap.height * budget.upscaleFactor).roundToInt().coerceAtLeast(1),
            true,
        )
        val deadline = elapsedRealtime() + budget.timeoutMillis
        val timing = performanceTelemetry?.startOcr(
            CapturePerformanceTelemetry.OcrPath.REGION_SECOND_PASS,
            secondBitmap.width.toLong() * secondBitmap.height,
        )
        ocrEngine.recognize(
            secondBitmap,
            OcrRequest(ocrProfile, passIndex = 2, roiIdentity = "region"),
        ) { result ->
            timing?.let { performanceTelemetry?.finishOcr(it, result.isSuccess) }
            secondBitmap.recycleSafely()
            bitmap.recycleSafely()
            if (!gate.isCurrent(frameGeneration)) {
                gate.release()
                return@recognize
            }
            val second = result.getOrNull()
            val withinDeadline = elapsedRealtime() <= deadline
            if (!withinDeadline) performanceTelemetry?.recordSecondPassTimeout()
            val merged = second
                ?.takeIf { withinDeadline }
                ?.let { secondResult ->
                    OcrSecondPassPolicy.merge(first, secondResult).also { combined ->
                        performanceTelemetry?.recordDeduplicatedOcrBlocks(
                            first.regions.size + secondResult.regions.size - combined.regions.size,
                        )
                    }
                }
                ?: first
            processRecognition(merged, frameGeneration)
        }
    }

    private fun processRecognition(
        recognition: OcrEngine.Recognition,
        frameGeneration: Long,
    ) {
        val recognitionBlocks = recognition.blocks.ifEmpty {
            recognition.regions.map { it.text }.ifEmpty {
                recognition.text.lineSequence().toList()
            }
        }
        val restoredBlocks = OcrPunctuationRestorer.restoreBlocks(
            recognitionBlocks,
            sourceLanguageTag,
        )
        val filteredBlocks = SourceTextFilter.filterBlocks(
            blocks = restoredBlocks,
            sourceLanguageTag = sourceLanguageTag,
            targetLanguageTag = targetLanguageTag,
        )
        val filteredText = filteredBlocks.joinToString("\n")
        if (filteredText.isBlank()) {
            clearWhenNoSourceText()
            gate.release()
            return
        }

        val stableText = stableTextGate.offer(filteredText)
        if (stableText == null) {
            gate.release()
        } else if (translationEngine.inputMode == TranslationInputMode.WHOLE_REGION) {
            gate.release()
            hadAcceptedSource = true
            onOriginalRecognized(stableText)
            val plan = SegmentedTextPlanner.plan(
                text = stableText,
                sourceLanguageTag = sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
            )
            synchronized(wholeRegionPlans) {
                wholeRegionPlans[plan.requestText] = plan
            }
            checkNotNull(wholeRegionCoordinator).submit(plan.requestText)
        } else {
            hadAcceptedSource = true
            translate(stableText, filteredBlocks, frameGeneration)
        }
    }

    private fun clearWhenNoSourceText() {
        stableTextGate.reset()
        wholeRegionCoordinator?.reset()
        synchronized(wholeRegionPlans) { wholeRegionPlans.clear() }
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
            val segmentedPlan = SegmentedTextPlanner.plan(
                text = part,
                sourceLanguageTag = sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
            )
            if (segmentedPlan.translatedSpanCount == 0) {
                translated[index] = part
                if (remaining.decrementAndGet() == 0) {
                    settle { publish(originalText, translationPlan, translated, frameGeneration) }
                }
                return@forEachIndexed
            }
            cached(segmentedPlan.requestText)?.let { hit ->
                performanceTelemetry?.recordTranslationCacheHit()
                translated[index] = hit
                if (remaining.decrementAndGet() == 0) {
                    settle { publish(originalText, translationPlan, translated, frameGeneration) }
                }
                return@forEachIndexed
            }

            val timing = performanceTelemetry?.startTranslation()
            translationEngine.translate(segmentedPlan.requestText) { translation ->
                timing?.let {
                    performanceTelemetry?.finishTranslation(it, translation.isSuccess)
                }
                translation.fold(
                    onSuccess = { text ->
                        runCatching { segmentedPlan.restore(text) }.fold(
                            onSuccess = { restored ->
                                translated[index] = restored
                                cache(segmentedPlan.requestText, restored)
                                if (remaining.decrementAndGet() == 0) {
                                    settle {
                                        publish(
                                            originalText,
                                            translationPlan,
                                            translated,
                                            frameGeneration,
                                        )
                                    }
                                }
                            },
                            onFailure = { error ->
                                settle {
                                    if (gate.isCurrent(frameGeneration)) onError(error)
                                }
                            },
                        )
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
        performanceTelemetry?.recordTranslationPublished()
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
        synchronized(wholeRegionPlans) { wholeRegionPlans.clear() }
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
