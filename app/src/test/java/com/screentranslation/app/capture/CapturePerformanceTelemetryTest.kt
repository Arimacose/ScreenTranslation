package com.screentranslation.app.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePerformanceTelemetryTest {
    @Test
    fun `snapshot records text-free frame OCR translation and interval counters`() {
        var now = 1_000_000_000L
        val telemetry = CapturePerformanceTelemetry(
            captureMode = "full_screen_incremental",
            baseIntervalMs = 450L,
            elapsedRealtimeNanos = { now },
            wallClockMillis = { 42L },
        )

        telemetry.recordFrameAvailable()
        telemetry.recordFrameAdmitted()
        now += 2_000_000L
        telemetry.recordSignatureScan(
            durationNanos = 2_000_000L,
            naturalTileCount = 1,
            scheduledTileCount = 2,
            intervalMs = 450L,
        )
        telemetry.recordBitmapMaterialized(1440L * 3200L * 4L)
        telemetry.recordBitmapSkipped(1440L * 3200L * 4L)

        val cold = telemetry.startOcr(
            CapturePerformanceTelemetry.OcrPath.FULL_FRAME,
            1440L * 3200L,
        )
        now += 10_000_000L
        telemetry.finishOcr(cold, successful = true)
        val warm = telemetry.startOcr(CapturePerformanceTelemetry.OcrPath.TILE, 200L * 300L)
        now += 4_000_000L
        telemetry.finishOcr(warm, successful = false)

        val translation = telemetry.startTranslation()
        now += 7_000_000L
        telemetry.finishTranslation(translation, successful = true)
        telemetry.recordTranslationCacheHit()
        telemetry.recordTranslationPublished(2)
        telemetry.recordLifecycleReset()
        telemetry.recordEnabled(false)
        telemetry.recordEnabled(true)
        telemetry.recordProcessingError()

        val snapshot = telemetry.snapshot("test")
        assertEquals(1L, snapshot.framesAvailable)
        assertEquals(2L, snapshot.ocrCalls)
        assertEquals(1L, snapshot.ocrSuccesses)
        assertEquals(1L, snapshot.ocrFailures)
        assertEquals(1L, snapshot.fullFrameOcrCalls)
        assertEquals(1L, snapshot.tileOcrCalls)
        assertEquals(10.0, snapshot.ocrLatency.maximumMs!!, 0.001)
        assertEquals(4.0, snapshot.warmOcrLatency.medianMs!!, 0.001)
        assertEquals(7.0, snapshot.translationLatency.medianMs!!, 0.001)
        assertEquals(2L, snapshot.translationsPublished)
        assertEquals(1L, snapshot.bitmapSkips)
        assertEquals(1L, snapshot.processingErrors)

        val json = JSONObject(snapshot.toJsonLine())
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("full_screen_incremental", json.getString("capture_mode"))
        assertEquals(2L, json.getLong("ocr_calls"))
        assertEquals(2L, json.getJSONObject("interval_observations").getLong("450"))
        assertEquals(4.0, json.getJSONObject("warm_ocr_latency_ms").getDouble("median"), 0.001)
        assertTrue(snapshot.toJsonLine().none { it == '\n' || it == '\r' })
    }

    @Test
    fun `nearest-rank percentiles and empty warm sample remain deterministic`() {
        var now = 0L
        val telemetry = CapturePerformanceTelemetry(
            captureMode = "region",
            baseIntervalMs = 750L,
            elapsedRealtimeNanos = { now },
            wallClockMillis = { 7L },
        )
        listOf(1L, 2L, 3L, 4L, 100L).forEach { latencyMs ->
            val token = telemetry.startOcr(CapturePerformanceTelemetry.OcrPath.REGION, 1L)
            now += latencyMs * 1_000_000L
            telemetry.finishOcr(token, successful = true)
        }

        val snapshot = telemetry.snapshot("percentiles")
        assertEquals(3.0, snapshot.ocrLatency.medianMs!!, 0.001)
        assertEquals(100.0, snapshot.ocrLatency.p95Ms!!, 0.001)
        assertEquals(3.0, snapshot.warmOcrLatency.medianMs!!, 0.001)
    }
}
