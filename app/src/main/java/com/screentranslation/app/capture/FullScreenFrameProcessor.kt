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
import com.screentranslation.app.ml.TranslationCall
import com.screentranslation.app.util.OcrPunctuationRestorer
import com.screentranslation.app.util.SegmentedTextPlan
import com.screentranslation.app.util.SegmentedTextPlanner
import com.screentranslation.app.util.SourceTextFilter
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Experimental full-screen pipeline.
 *
 * A low-resolution luminance pass identifies changed tiles before OCR. Dirty
 * tiles receive one forced verification pass, block identity is preserved by
 * geometry/text matching, and only stable changed blocks enter a single-flight
 * translation queue. Results retain normalized screen geometry for overlays.
 */
class FullScreenFrameProcessor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationBackend,
    private val sourceLanguageTag: String,
    private val targetLanguageTag: String,
    private val ocrProfile: OcrProfile = OcrProfiles.BALANCED,
    frameIntervalMs: Long,
    private val onBlocks: (List<TranslatedScreenBlock>) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val performanceTelemetry: CapturePerformanceTelemetry? = null,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : FramePipeline {
    data class TranslatedScreenBlock(
        val id: Long,
        val originalText: String,
        val translatedText: String,
        val bounds: NormalizedBounds,
    )

    private data class TranslationValue(val sourceText: String, val translatedText: String)

    private data class MaterializedFrame(
        val bitmap: Bitmap,
        val tiles: List<PixelTile>,
        val changes: TileChangeSet,
    )

    private val gate = FrameGate(frameIntervalMs, elapsedRealtime)
    private val adaptiveInterval = AdaptiveFrameInterval(frameIntervalMs)
    private val signatureDiffer = TileSignatureDiffer()
    private val blockTracker = IncrementalBlockTracker()
    private val tileBlocks = mutableMapOf<Int, List<ScreenTextBlock>>()
    private val translations = mutableMapOf<Long, TranslationValue>()
    private var verificationTiles = emptySet<Int>()
    private var lastExclusions = emptyList<RectF>()
    private var enabled = true
    @Volatile
    private var currentBlocks = emptyList<TrackedScreenTextBlock>()
    private val translationQueue = BlockTranslationQueue(
        backend = translationEngine,
        sourceLanguageTag = sourceLanguageTag,
        targetLanguageTag = targetLanguageTag,
        onTranslation = { id, source, translation ->
            synchronized(translations) {
                translations[id] = TranslationValue(source, translation)
            }
            performanceTelemetry?.recordTranslationPublished()
            publishBlocks()
        },
        onError = onError,
        performanceTelemetry = performanceTelemetry,
    )

    override fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        performanceTelemetry?.recordEnabled(value)
        gate.setEnabled(value)
        if (value) {
            translationQueue.resume()
        } else {
            translationQueue.pause()
            clearTrackingState()
        }
    }

    override fun resetStability() {
        performanceTelemetry?.recordLifecycleReset()
        gate.invalidate()
        clearTrackingState()
    }

    private fun clearTrackingState() {
        signatureDiffer.reset()
        blockTracker.reset()
        adaptiveInterval.reset()
        gate.setFrameIntervalMs(adaptiveInterval.currentIntervalMs)
        tileBlocks.clear()
        verificationTiles = emptySet()
        lastExclusions = emptyList()
        currentBlocks = emptyList()
        translationQueue.reset()
        synchronized(translations) { translations.clear() }
        onBlocks(emptyList())
    }

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

        val generation = gate.tryAcquire()
        if (generation == null) {
            performanceTelemetry?.recordFrameRejected()
            image.close()
            return
        }
        performanceTelemetry?.recordFrameAdmitted()

        val materialized = try {
            val signatureStartedAt = System.nanoTime()
            val signatureFrame = ImageTileSignatureExtractor.extract(
                image = image,
                normalizedRegion = normalizedRegion,
                normalizedExclusions = normalizedExclusions,
            )
            val exclusionChangedTiles = changedExclusionTiles(
                previous = lastExclusions,
                current = normalizedExclusions,
            )
            val hadSignatureBaseline = signatureDiffer.hasBaseline
            val changes = signatureDiffer.compare(
                current = signatureFrame.signatures,
                forced = verificationTiles,
                // Adding/removing a label changes which samples are white.
                // Ignore only tiles touched by changed mask rectangles;
                // suppressing the whole frame can hide a target-app switch.
                suppressedNaturalTiles = if (hadSignatureBaseline) {
                    exclusionChangedTiles
                } else {
                    emptySet()
                },
            )
            lastExclusions = normalizedExclusions.map(::RectF)
            val nextIntervalMs = adaptiveInterval.recordChanged(changes.natural.isNotEmpty())
            gate.setFrameIntervalMs(nextIntervalMs)
            performanceTelemetry?.recordSignatureScan(
                durationNanos = System.nanoTime() - signatureStartedAt,
                naturalTileCount = changes.natural.size,
                scheduledTileCount = changes.all.size,
                intervalMs = nextIntervalMs,
            )
            if (changes.natural.isNotEmpty()) {
                val visibleBlocks = blocksOutsideTiles(currentBlocks, changes.natural)
                if (visibleBlocks.size != currentBlocks.size) {
                    currentBlocks = visibleBlocks
                    // A changed source must never retain its old translation while
                    // OCR and translation for the replacement are still in flight.
                    publishBlocks()
                }
            }
            if (changes.all.isEmpty()) {
                performanceTelemetry?.recordBitmapSkipped(signatureFrame.avoidedBitmapBytes)
                gate.release()
                return
            }
            val bitmap = BitmapExtractor.extract(
                image = image,
                normalizedRegion = normalizedRegion,
                normalizedExclusions = normalizedExclusions,
            )
            performanceTelemetry?.recordBitmapMaterialized(bitmap.allocationByteCount.toLong())
            MaterializedFrame(bitmap, signatureFrame.tiles, changes)
        } catch (error: Throwable) {
            gate.release()
            onError(error)
            return
        } finally {
            image.close()
        }

        val bitmap = materialized.bitmap
        val tiles = materialized.tiles
        val changes = materialized.changes
        val tileIndices = changes.all.sorted()
        if (tileIndices.size >= FULL_FRAME_OCR_TILE_THRESHOLD) {
            recognizeWholeFrame(bitmap, tiles, naturallyChanged = changes.natural, generation)
        } else {
            recognizeTiles(
                bitmap = bitmap,
                tiles = tiles,
                tileIndices = tileIndices,
                naturallyChanged = changes.natural,
                generation = generation,
            )
        }
    }

    /** A single full-frame inference is cheaper than starting many independent tile runs. */
    private fun recognizeWholeFrame(
        bitmap: Bitmap,
        tiles: List<PixelTile>,
        naturallyChanged: Set<Int>,
        generation: Long,
    ) {
        val timing = performanceTelemetry?.startOcr(
            CapturePerformanceTelemetry.OcrPath.FULL_FRAME,
            bitmap.width.toLong() * bitmap.height,
        )
        ocrEngine.recognize(
            bitmap,
            OcrRequest(ocrProfile, passIndex = 1, roiIdentity = "full_frame"),
        ) { result ->
            timing?.let { performanceTelemetry?.finishOcr(it, result.isSuccess) }
            if (!gate.isCurrent(generation)) {
                bitmap.recycleSafely()
                gate.release()
                return@recognize
            }
            result.fold(
                onSuccess = { recognition ->
                    val grouped = tiles.associate { it.index to mutableListOf<ScreenTextBlock>() }
                    recognition.regions.forEach { region ->
                        val sourceText = SourceTextFilter.filter(
                            text = OcrPunctuationRestorer.restore(
                                region.text,
                                sourceLanguageTag,
                            ),
                            sourceLanguageTag = sourceLanguageTag,
                            targetLanguageTag = targetLanguageTag,
                        ) ?: return@forEach
                        val bounds = NormalizedBounds(
                            left = region.left,
                            top = region.top,
                            right = region.right,
                            bottom = region.bottom,
                        )
                        val centerX = bounds.centerX * bitmap.width
                        val centerY = bounds.centerY * bitmap.height
                        val owner = tiles.firstOrNull { it.contains(centerX, centerY) }
                        if (owner != null) {
                            grouped.getValue(owner.index) += ScreenTextBlock(
                                sourceText,
                                bounds,
                                region.confidence,
                            )
                        }
                    }
                    grouped.forEach { (index, blocks) -> tileBlocks[index] = blocks }
                    bitmap.recycleSafely()
                    finishRecognition(naturallyChanged, generation)
                },
                onFailure = { error ->
                    bitmap.recycleSafely()
                    gate.release()
                    onError(error)
                },
            )
        }
    }

    private fun recognizeTiles(
        bitmap: Bitmap,
        tiles: List<PixelTile>,
        tileIndices: List<Int>,
        naturallyChanged: Set<Int>,
        generation: Long,
        position: Int = 0,
    ) {
        if (!gate.isCurrent(generation)) {
            bitmap.recycleSafely()
            gate.release()
            return
        }
        if (position >= tileIndices.size) {
            bitmap.recycleSafely()
            finishRecognition(naturallyChanged, generation)
            return
        }

        val baseTile = tiles[tileIndices[position]]
        val cropTile = expandedTile(baseTile, bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(
            bitmap,
            cropTile.left,
            cropTile.top,
            cropTile.width,
            cropTile.height,
        )
        val timing = performanceTelemetry?.startOcr(
            CapturePerformanceTelemetry.OcrPath.TILE,
            crop.width.toLong() * crop.height,
        )
        val request = OcrRequest(
            ocrProfile,
            passIndex = 1,
            roiIdentity = "tile-${baseTile.index}",
        )
        ocrEngine.recognize(crop, request) { result ->
            timing?.let { performanceTelemetry?.finishOcr(it, result.isSuccess) }
            if (!gate.isCurrent(generation)) {
                crop.recycleSafely()
                bitmap.recycleSafely()
                gate.release()
                return@recognize
            }
            result.fold(
                onSuccess = { recognition ->
                    if (OcrSecondPassPolicy.shouldRun(
                            request,
                            recognition,
                            crop.width,
                            crop.height,
                        )
                    ) {
                        recognizeTileSecondPass(
                            bitmap = bitmap,
                            crop = crop,
                            cropTile = cropTile,
                            baseTile = baseTile,
                            tiles = tiles,
                            tileIndices = tileIndices,
                            naturallyChanged = naturallyChanged,
                            generation = generation,
                            position = position,
                            first = recognition,
                        )
                    } else {
                        crop.recycleSafely()
                        finishTileRecognition(
                            bitmap,
                            cropTile,
                            baseTile,
                            tiles,
                            tileIndices,
                            naturallyChanged,
                            generation,
                            position,
                            recognition,
                        )
                    }
                },
                onFailure = { error ->
                    crop.recycleSafely()
                    bitmap.recycleSafely()
                    gate.release()
                    onError(error)
                },
            )
        }
    }

    private fun recognizeTileSecondPass(
        bitmap: Bitmap,
        crop: Bitmap,
        cropTile: PixelTile,
        baseTile: PixelTile,
        tiles: List<PixelTile>,
        tileIndices: List<Int>,
        naturallyChanged: Set<Int>,
        generation: Long,
        position: Int,
        first: OcrEngine.Recognition,
    ) {
        val budget = checkNotNull(ocrProfile.secondPass)
        val secondBitmap = Bitmap.createScaledBitmap(
            crop,
            (crop.width * budget.upscaleFactor).roundToInt().coerceAtLeast(1),
            (crop.height * budget.upscaleFactor).roundToInt().coerceAtLeast(1),
            true,
        )
        crop.recycleSafely()
        val startedAt = SystemClock.elapsedRealtime()
        val timing = performanceTelemetry?.startOcr(
            CapturePerformanceTelemetry.OcrPath.TILE_SECOND_PASS,
            secondBitmap.width.toLong() * secondBitmap.height,
        )
        ocrEngine.recognize(
            secondBitmap,
            OcrRequest(ocrProfile, passIndex = 2, roiIdentity = "tile-${baseTile.index}"),
        ) { result ->
            timing?.let { performanceTelemetry?.finishOcr(it, result.isSuccess) }
            secondBitmap.recycleSafely()
            if (!gate.isCurrent(generation)) {
                bitmap.recycleSafely()
                gate.release()
                return@recognize
            }
            val withinDeadline = SystemClock.elapsedRealtime() - startedAt <= budget.timeoutMillis
            if (!withinDeadline) performanceTelemetry?.recordSecondPassTimeout()
            val merged = result.getOrNull()
                ?.takeIf { withinDeadline }
                ?.let { second ->
                    OcrSecondPassPolicy.merge(first, second).also { combined ->
                        performanceTelemetry?.recordDeduplicatedOcrBlocks(
                            first.regions.size + second.regions.size - combined.regions.size,
                        )
                    }
                }
                ?: first
            finishTileRecognition(
                bitmap,
                cropTile,
                baseTile,
                tiles,
                tileIndices,
                naturallyChanged,
                generation,
                position,
                merged,
            )
        }
    }

    private fun finishTileRecognition(
        bitmap: Bitmap,
        cropTile: PixelTile,
        baseTile: PixelTile,
        tiles: List<PixelTile>,
        tileIndices: List<Int>,
        naturallyChanged: Set<Int>,
        generation: Long,
        position: Int,
        recognition: OcrEngine.Recognition,
    ) {
        tileBlocks[baseTile.index] = recognition.regions.mapNotNull { region ->
            val sourceText = SourceTextFilter.filter(
                text = OcrPunctuationRestorer.restore(region.text, sourceLanguageTag),
                sourceLanguageTag = sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
            ) ?: return@mapNotNull null
            val bounds = mapTileRegionToScreen(
                tileCrop = cropTile,
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
            )
            val centerX = bounds.centerX * bitmap.width
            val centerY = bounds.centerY * bitmap.height
            if (!baseTile.contains(centerX, centerY)) null else {
                ScreenTextBlock(sourceText, bounds, region.confidence)
            }
        }
        recognizeTiles(
            bitmap,
            tiles,
            tileIndices,
            naturallyChanged,
            generation,
            position + 1,
        )
    }

    private fun finishRecognition(naturallyChanged: Set<Int>, generation: Long) {
        if (!gate.isCurrent(generation)) {
            gate.release()
            return
        }
        currentBlocks = blockTracker.update(
            rawBlocks = tileBlocks.values.flatten(),
            invalidatedTiles = naturallyChanged,
        )
        verificationTiles = verificationTileIndices(naturallyChanged, currentBlocks)
        val activeIds = currentBlocks.mapTo(linkedSetOf()) { it.id }
        synchronized(translations) { translations.keys.retainAll(activeIds) }
        translationQueue.synchronize(activeIds)
        publishBlocks()

        currentBlocks.asSequence()
            .filter { it.isStable && it.text.isNotBlank() }
            .filter { block ->
                synchronized(translations) { translations[block.id]?.sourceText != block.text }
            }
            .take(MAX_CHANGED_BLOCKS_PER_SCAN)
            .forEach { block -> translationQueue.submit(block.id, block.text) }
        gate.release()
    }

    private fun publishBlocks() {
        if (gate.isClosed) return
        val snapshot = synchronized(translations) { translations.toMap() }
        onBlocks(
            currentBlocks.mapNotNull { block ->
                val translation = snapshot[block.id]
                    ?.takeIf { it.sourceText == block.text }
                    ?: return@mapNotNull null
                TranslatedScreenBlock(
                    id = block.id,
                    originalText = block.text,
                    translatedText = translation.translatedText,
                    bounds = block.bounds,
                )
            },
        )
    }

    private fun expandedTile(tile: PixelTile, width: Int, height: Int): PixelTile {
        val margin = max(MIN_TILE_OVERLAP_PX, minOf(width, height) / 80)
        return tile.copy(
            left = (tile.left - margin).coerceAtLeast(0),
            top = (tile.top - margin).coerceAtLeast(0),
            right = (tile.right + margin).coerceAtMost(width),
            bottom = (tile.bottom + margin).coerceAtMost(height),
        )
    }

    private fun changedExclusionTiles(
        previous: List<RectF>,
        current: List<RectF>,
    ): Set<Int> {
        val changed = previous.filter { old ->
            current.none { new -> equivalentExclusion(old, new) }
        } + current.filter { new ->
            previous.none { old -> equivalentExclusion(old, new) }
        }
        return changed.flatMapTo(linkedSetOf(), ::tilesIntersecting)
    }

    private fun equivalentExclusion(left: RectF, right: RectF): Boolean =
        abs(left.left - right.left) <= EXCLUSION_COORDINATE_EPSILON &&
            abs(left.top - right.top) <= EXCLUSION_COORDINATE_EPSILON &&
            abs(left.right - right.right) <= EXCLUSION_COORDINATE_EPSILON &&
            abs(left.bottom - right.bottom) <= EXCLUSION_COORDINATE_EPSILON

    private fun tilesIntersecting(bounds: RectF): List<Int> {
        val leftColumn = (bounds.left * TILE_COLUMNS).toInt().coerceIn(0, TILE_COLUMNS - 1)
        val rightColumn = ((bounds.right - EXCLUSION_COORDINATE_EPSILON) * TILE_COLUMNS)
            .toInt()
            .coerceIn(leftColumn, TILE_COLUMNS - 1)
        val topRow = (bounds.top * TILE_ROWS).toInt().coerceIn(0, TILE_ROWS - 1)
        val bottomRow = ((bounds.bottom - EXCLUSION_COORDINATE_EPSILON) * TILE_ROWS)
            .toInt()
            .coerceIn(topRow, TILE_ROWS - 1)
        return buildList {
            for (row in topRow..bottomRow) {
                for (column in leftColumn..rightColumn) {
                    add(row * TILE_COLUMNS + column)
                }
            }
        }
    }

    override fun close() {
        if (gate.isClosed) return
        gate.close()
        translationQueue.close()
        blockTracker.reset()
        tileBlocks.clear()
        synchronized(translations) { translations.clear() }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private companion object {
        private const val MIN_TILE_OVERLAP_PX = 16
        private const val MAX_CHANGED_BLOCKS_PER_SCAN = 12
        private const val FULL_FRAME_OCR_TILE_THRESHOLD = 6
        private const val EXCLUSION_COORDINATE_EPSILON = 0.0005f
        private const val TILE_COLUMNS = 3
        private const val TILE_ROWS = 6
    }
}

internal class BlockTranslationQueue(
    private val backend: TranslationBackend,
    private val onTranslation: (Long, String, String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val performanceTelemetry: CapturePerformanceTelemetry? = null,
    private val sourceLanguageTag: String = "en",
    private val targetLanguageTag: String = "zh",
) : AutoCloseable {
    private data class Work(val id: Long, val text: String)
    private data class Active(
        val token: Long,
        val work: Work,
        val plan: SegmentedTextPlan,
        var call: TranslationCall,
        var performanceToken: CapturePerformanceTelemetry.TimingToken? = null,
    )

    private val pending = linkedMapOf<Long, Work>()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 64
    }
    private var active: Active? = null
    private var nextToken = 1L
    private var paused = false
    private var closed = false

    fun submit(id: Long, text: String) {
        var callToCancel: TranslationCall? = null
        synchronized(this) {
            if (closed) return
            val current = active
            if (current?.work?.id == id) {
                if (current.work.text == text) return
                active = null
                callToCancel = current.call
            }
            pending[id] = Work(id, text)
        }
        callToCancel?.cancel()
        pump()
    }

    fun synchronize(activeIds: Set<Long>) {
        var callToCancel: TranslationCall? = null
        synchronized(this) {
            pending.keys.retainAll(activeIds)
            val current = active
            if (current != null && current.work.id !in activeIds) {
                active = null
                callToCancel = current.call
            }
        }
        callToCancel?.cancel()
        pump()
    }

    fun pause() {
        synchronized(this) { paused = true }
    }

    fun resume() {
        synchronized(this) {
            if (closed) return
            paused = false
        }
        pump()
    }

    fun reset() {
        val call = synchronized(this) {
            pending.clear()
            active?.call.also { active = null }
        }
        call?.cancel()
    }

    private fun pump() {
        val activeWork = synchronized(this) {
            if (closed || paused || active != null || pending.isEmpty()) return
            val work = pending.entries.first().also { pending.remove(it.key) }.value
            val plan = SegmentedTextPlanner.plan(
                text = work.text,
                sourceLanguageTag = sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
            )
            if (plan.translatedSpanCount == 0) {
                onTranslation(work.id, work.text, work.text)
                return@synchronized null
            }
            Active(nextToken++, work, plan, TranslationCall.NONE).also { active = it }
        }
        if (activeWork == null) {
            pump()
            return
        }
        val cached = synchronized(cache) { cache[activeWork.plan.requestText] }
        if (cached != null) {
            performanceTelemetry?.recordTranslationCacheHit()
            complete(activeWork.token, Result.success(cached))
            return
        }

        activeWork.performanceToken = performanceTelemetry?.startTranslation()
        val call = backend.translate(activeWork.plan.requestText) { result ->
            complete(activeWork.token, result.mapCatching(activeWork.plan::restore))
        }
        synchronized(this) {
            if (active?.token == activeWork.token) {
                active?.call = call
            } else {
                call.cancel()
            }
        }
    }

    private fun complete(token: Long, result: Result<String>) {
        val completed = synchronized(this) {
            val current = active?.takeIf { it.token == token } ?: return
            active = null
            current.performanceToken?.let {
                performanceTelemetry?.finishTranslation(it, result.isSuccess)
            }
            current
        }
        result.fold(
            onSuccess = { translated ->
                synchronized(cache) { cache[completed.plan.requestText] = translated }
                onTranslation(completed.work.id, completed.work.text, translated)
            },
            onFailure = { error ->
                if (error !is CancellationException) onError(error)
            },
        )
        pump()
    }

    override fun close() {
        var unfinishedToken: CapturePerformanceTelemetry.TimingToken? = null
        val call = synchronized(this) {
            if (closed) return
            closed = true
            pending.clear()
            unfinishedToken = active?.performanceToken
            active?.call.also { active = null }
        }
        call?.cancel()
        unfinishedToken?.let { performanceTelemetry?.finishTranslation(it, successful = false) }
        synchronized(cache) { cache.clear() }
    }
}
