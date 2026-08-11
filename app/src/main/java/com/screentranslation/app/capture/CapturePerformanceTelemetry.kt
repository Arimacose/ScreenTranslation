package com.screentranslation.app.capture

import java.util.Locale
import kotlin.math.ceil

/**
 * Text-free counters for repeatable capture endurance measurements.
 *
 * The recorder deliberately keeps no pixels, OCR text, or translations. A
 * service can publish [Snapshot.toJsonLine] to Logcat or its dumpsys output,
 * while the same snapshot remains directly testable on the JVM.
 */
class CapturePerformanceTelemetry(
    val captureMode: String,
    val baseIntervalMs: Long,
    private val elapsedRealtimeNanos: () -> Long = System::nanoTime,
    wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    enum class OcrPath {
        REGION,
        FULL_FRAME,
        TILE,
    }

    class TimingToken internal constructor(val startedAtNanos: Long)

    data class LatencySummary(
        val count: Long,
        val sampledCount: Int,
        val minimumMs: Double?,
        val medianMs: Double?,
        val p95Ms: Double?,
        val maximumMs: Double?,
    )

    data class Snapshot(
        val sessionId: String,
        val reason: String,
        val captureMode: String,
        val baseIntervalMs: Long,
        val elapsedMs: Long,
        val framesAvailable: Long,
        val framesAdmitted: Long,
        val framesRejected: Long,
        val signatureScans: Long,
        val naturallyChangedFrames: Long,
        val naturalDirtyTiles: Long,
        val scheduledDirtyTiles: Long,
        val bitmapMaterializations: Long,
        val bitmapMaterializedBytes: Long,
        val bitmapSkips: Long,
        val bitmapSkippedBytes: Long,
        val ocrCalls: Long,
        val ocrSuccesses: Long,
        val ocrFailures: Long,
        val regionOcrCalls: Long,
        val fullFrameOcrCalls: Long,
        val tileOcrCalls: Long,
        val ocrInputPixels: Long,
        val translationCalls: Long,
        val translationSuccesses: Long,
        val translationFailures: Long,
        val translationCacheHits: Long,
        val translationsPublished: Long,
        val lifecycleResets: Long,
        val disabledTransitions: Long,
        val enabledTransitions: Long,
        val processingErrors: Long,
        val currentIntervalMs: Long,
        val minimumIntervalMs: Long,
        val maximumIntervalMs: Long,
        val intervalObservations: Map<Long, Long>,
        val signatureLatency: LatencySummary,
        val ocrLatency: LatencySummary,
        val warmOcrLatency: LatencySummary,
        val translationLatency: LatencySummary,
    ) {
        fun toJsonLine(): String = buildString(1_536) {
            append('{')
            field("schema_version", 1)
            field("session_id", sessionId)
            field("reason", reason)
            field("capture_mode", captureMode)
            field("base_interval_ms", baseIntervalMs)
            field("elapsed_ms", elapsedMs)
            field("frames_available", framesAvailable)
            field("frames_admitted", framesAdmitted)
            field("frames_rejected", framesRejected)
            field("signature_scans", signatureScans)
            field("naturally_changed_frames", naturallyChangedFrames)
            field("natural_dirty_tiles", naturalDirtyTiles)
            field("scheduled_dirty_tiles", scheduledDirtyTiles)
            field("bitmap_materializations", bitmapMaterializations)
            field("bitmap_materialized_bytes", bitmapMaterializedBytes)
            field("bitmap_skips", bitmapSkips)
            field("bitmap_skipped_bytes", bitmapSkippedBytes)
            field("ocr_calls", ocrCalls)
            field("ocr_successes", ocrSuccesses)
            field("ocr_failures", ocrFailures)
            field("region_ocr_calls", regionOcrCalls)
            field("full_frame_ocr_calls", fullFrameOcrCalls)
            field("tile_ocr_calls", tileOcrCalls)
            field("ocr_input_pixels", ocrInputPixels)
            field("translation_calls", translationCalls)
            field("translation_successes", translationSuccesses)
            field("translation_failures", translationFailures)
            field("translation_cache_hits", translationCacheHits)
            field("translations_published", translationsPublished)
            field("lifecycle_resets", lifecycleResets)
            field("disabled_transitions", disabledTransitions)
            field("enabled_transitions", enabledTransitions)
            field("processing_errors", processingErrors)
            field("current_interval_ms", currentIntervalMs)
            field("minimum_interval_ms", minimumIntervalMs)
            field("maximum_interval_ms", maximumIntervalMs)
            append("\"interval_observations\":{")
            intervalObservations.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                quoted(entry.key.toString())
                append(':').append(entry.value)
            }
            append("},")
            latency("signature_latency_ms", signatureLatency)
            append(',')
            latency("ocr_latency_ms", ocrLatency)
            append(',')
            latency("warm_ocr_latency_ms", warmOcrLatency)
            append(',')
            latency("translation_latency_ms", translationLatency)
            append('}')
        }

        private fun StringBuilder.field(name: String, value: String) {
            quoted(name)
            append(':')
            quoted(value)
            append(',')
        }

        private fun StringBuilder.field(name: String, value: Long) {
            quoted(name)
            append(':').append(value).append(',')
        }

        private fun StringBuilder.field(name: String, value: Int) =
            field(name, value.toLong())

        private fun StringBuilder.latency(name: String, value: LatencySummary) {
            quoted(name)
            append(":{")
            field("count", value.count)
            field("sampled_count", value.sampledCount)
            nullableNumber("minimum", value.minimumMs)
            append(',')
            nullableNumber("median", value.medianMs)
            append(',')
            nullableNumber("p95", value.p95Ms)
            append(',')
            nullableNumber("maximum", value.maximumMs)
            append('}')
        }

        private fun StringBuilder.nullableNumber(name: String, value: Double?) {
            quoted(name)
            append(':')
            if (value == null) {
                append("null")
            } else {
                append(String.format(Locale.ROOT, "%.3f", value))
            }
        }

        private fun StringBuilder.quoted(value: String) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
    }

    private val startedAtNanos = elapsedRealtimeNanos()
    private val sessionId = "%x-%x".format(
        Locale.ROOT,
        wallClockMillis(),
        startedAtNanos,
    )
    private var framesAvailable = 0L
    private var framesAdmitted = 0L
    private var framesRejected = 0L
    private var signatureScans = 0L
    private var naturallyChangedFrames = 0L
    private var naturalDirtyTiles = 0L
    private var scheduledDirtyTiles = 0L
    private var bitmapMaterializations = 0L
    private var bitmapMaterializedBytes = 0L
    private var bitmapSkips = 0L
    private var bitmapSkippedBytes = 0L
    private var ocrCalls = 0L
    private var ocrSuccesses = 0L
    private var ocrFailures = 0L
    private var regionOcrCalls = 0L
    private var fullFrameOcrCalls = 0L
    private var tileOcrCalls = 0L
    private var ocrInputPixels = 0L
    private var translationCalls = 0L
    private var translationSuccesses = 0L
    private var translationFailures = 0L
    private var translationCacheHits = 0L
    private var translationsPublished = 0L
    private var lifecycleResets = 0L
    private var disabledTransitions = 0L
    private var enabledTransitions = 0L
    private var processingErrors = 0L
    private var currentIntervalMs = baseIntervalMs
    private var minimumIntervalMs = baseIntervalMs
    private var maximumIntervalMs = baseIntervalMs
    private val intervalObservations = sortedMapOf<Long, Long>()
    private val signatureLatenciesMs = ArrayList<Double>()
    private val ocrLatenciesMs = ArrayList<Double>()
    private val translationLatenciesMs = ArrayList<Double>()

    init {
        require(captureMode.isNotBlank())
        require(baseIntervalMs >= 0L)
        intervalObservations[baseIntervalMs] = 1L
    }

    @Synchronized
    fun recordFrameAvailable() {
        framesAvailable += 1L
    }

    @Synchronized
    fun recordFrameAdmitted() {
        framesAdmitted += 1L
    }

    @Synchronized
    fun recordFrameRejected() {
        framesRejected += 1L
    }

    @Synchronized
    fun recordSignatureScan(
        durationNanos: Long,
        naturalTileCount: Int,
        scheduledTileCount: Int,
        intervalMs: Long,
    ) {
        signatureScans += 1L
        if (naturalTileCount > 0) naturallyChangedFrames += 1L
        naturalDirtyTiles += naturalTileCount.coerceAtLeast(0)
        scheduledDirtyTiles += scheduledTileCount.coerceAtLeast(0)
        addLatency(signatureLatenciesMs, durationNanos)
        currentIntervalMs = intervalMs
        minimumIntervalMs = minOf(minimumIntervalMs, intervalMs)
        maximumIntervalMs = maxOf(maximumIntervalMs, intervalMs)
        intervalObservations[intervalMs] = intervalObservations.getOrDefault(intervalMs, 0L) + 1L
    }

    @Synchronized
    fun recordBitmapMaterialized(bytes: Long) {
        bitmapMaterializations += 1L
        bitmapMaterializedBytes += bytes.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordBitmapSkipped(bytes: Long) {
        bitmapSkips += 1L
        bitmapSkippedBytes += bytes.coerceAtLeast(0L)
    }

    @Synchronized
    fun startOcr(path: OcrPath, inputPixels: Long): TimingToken {
        ocrCalls += 1L
        ocrInputPixels += inputPixels.coerceAtLeast(0L)
        when (path) {
            OcrPath.REGION -> regionOcrCalls += 1L
            OcrPath.FULL_FRAME -> fullFrameOcrCalls += 1L
            OcrPath.TILE -> tileOcrCalls += 1L
        }
        return TimingToken(elapsedRealtimeNanos())
    }

    @Synchronized
    fun finishOcr(token: TimingToken, successful: Boolean) {
        if (successful) ocrSuccesses += 1L else ocrFailures += 1L
        addLatency(ocrLatenciesMs, elapsedRealtimeNanos() - token.startedAtNanos)
    }

    @Synchronized
    fun startTranslation(): TimingToken {
        translationCalls += 1L
        return TimingToken(elapsedRealtimeNanos())
    }

    @Synchronized
    fun finishTranslation(token: TimingToken, successful: Boolean) {
        if (successful) translationSuccesses += 1L else translationFailures += 1L
        addLatency(translationLatenciesMs, elapsedRealtimeNanos() - token.startedAtNanos)
    }

    @Synchronized
    fun recordTranslationCacheHit() {
        translationCacheHits += 1L
    }

    @Synchronized
    fun recordTranslationPublished(count: Int = 1) {
        translationsPublished += count.coerceAtLeast(0)
    }

    @Synchronized
    fun recordLifecycleReset() {
        lifecycleResets += 1L
    }

    @Synchronized
    fun recordEnabled(value: Boolean) {
        if (value) enabledTransitions += 1L else disabledTransitions += 1L
    }

    @Synchronized
    fun recordProcessingError() {
        processingErrors += 1L
    }

    @Synchronized
    fun snapshot(reason: String): Snapshot {
        val allOcr = latencySummary(ocrCalls, ocrLatenciesMs)
        val warmSamples = if (ocrLatenciesMs.size > 1) {
            ocrLatenciesMs.subList(1, ocrLatenciesMs.size)
        } else {
            emptyList()
        }
        return Snapshot(
            sessionId = sessionId,
            reason = reason,
            captureMode = captureMode,
            baseIntervalMs = baseIntervalMs,
            elapsedMs = ((elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L)
                .coerceAtLeast(0L),
            framesAvailable = framesAvailable,
            framesAdmitted = framesAdmitted,
            framesRejected = framesRejected,
            signatureScans = signatureScans,
            naturallyChangedFrames = naturallyChangedFrames,
            naturalDirtyTiles = naturalDirtyTiles,
            scheduledDirtyTiles = scheduledDirtyTiles,
            bitmapMaterializations = bitmapMaterializations,
            bitmapMaterializedBytes = bitmapMaterializedBytes,
            bitmapSkips = bitmapSkips,
            bitmapSkippedBytes = bitmapSkippedBytes,
            ocrCalls = ocrCalls,
            ocrSuccesses = ocrSuccesses,
            ocrFailures = ocrFailures,
            regionOcrCalls = regionOcrCalls,
            fullFrameOcrCalls = fullFrameOcrCalls,
            tileOcrCalls = tileOcrCalls,
            ocrInputPixels = ocrInputPixels,
            translationCalls = translationCalls,
            translationSuccesses = translationSuccesses,
            translationFailures = translationFailures,
            translationCacheHits = translationCacheHits,
            translationsPublished = translationsPublished,
            lifecycleResets = lifecycleResets,
            disabledTransitions = disabledTransitions,
            enabledTransitions = enabledTransitions,
            processingErrors = processingErrors,
            currentIntervalMs = currentIntervalMs,
            minimumIntervalMs = minimumIntervalMs,
            maximumIntervalMs = maximumIntervalMs,
            intervalObservations = intervalObservations.toMap(),
            signatureLatency = latencySummary(signatureScans, signatureLatenciesMs),
            ocrLatency = allOcr,
            warmOcrLatency = latencySummary((ocrCalls - 1L).coerceAtLeast(0L), warmSamples),
            translationLatency = latencySummary(translationCalls, translationLatenciesMs),
        )
    }

    private fun addLatency(destination: MutableList<Double>, durationNanos: Long) {
        if (destination.size >= MAX_LATENCY_SAMPLES) return
        destination += durationNanos.coerceAtLeast(0L) / 1_000_000.0
    }

    private fun latencySummary(count: Long, samples: List<Double>): LatencySummary {
        if (samples.isEmpty()) {
            return LatencySummary(count, 0, null, null, null, null)
        }
        val sorted = samples.sorted()
        return LatencySummary(
            count = count,
            sampledCount = sorted.size,
            minimumMs = sorted.first(),
            medianMs = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            maximumMs = sorted.last(),
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private companion object {
        const val MAX_LATENCY_SAMPLES = 32_768
    }
}
