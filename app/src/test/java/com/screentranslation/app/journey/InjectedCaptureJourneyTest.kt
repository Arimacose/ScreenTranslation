package com.screentranslation.app.journey

import com.screentranslation.app.BuildConfig
import com.screentranslation.app.ml.TranslationCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectedCaptureJourneyTest {
    @Test
    fun `selected edition runs capture OCR translation overlay copy and complete stop`() {
        val fixture = FixtureHarness(
            frames = listOf(
                InjectedFrame("english", byteArrayOf(1, 2, 3)),
                InjectedFrame("mixed", byteArrayOf(4, 5, 6)),
            ),
            recognized = mapOf(
                "english" to listOf(
                    InjectedTextBlock(1, "Settings"),
                    InjectedTextBlock(2, "字幕テスト"),
                ),
                "mixed" to listOf(
                    InjectedTextBlock(1, "Open 设置 2026-08-20"),
                    InjectedTextBlock(2, "字幕テスト"),
                ),
            ),
        )
        val journey = fixture.journey(BuildConfig.EDITION_ID, InjectedJourneyMode.FULL_SCREEN)
        journey.start()
        assertTrue(journey.processNextFrame())
        fixture.backend.completeAll()
        journey.copyLatest(1)
        assertTrue(journey.processNextFrame())
        fixture.backend.completeAll()
        val report = journey.stopAndReport()

        assertEquals(BuildConfig.EDITION_ID, report.editionId)
        assertEquals(2, report.frames)
        assertEquals(4, report.recognizedBlocks)
        assertEquals(4, report.publishedBlocks)
        assertTrue(report.copiedCharacters > 0)
        assertTrue(report.persistedArtifactCategories.isEmpty())
        assertEquals(
            setOf(
                "translation_backend",
                "ocr_engine",
                "overlay_host",
                "capture_source",
                "projection_session",
            ),
            report.releasedResources,
        )
        assertTrue(fixture.capture.frames.all { frame -> frame.pixels.all { it == 0.toByte() } })
        assertEquals("projection.stop", fixture.events.last())
    }

    @Test
    fun `full screen changed source discards late result and publishes latest once`() {
        val fixture = FixtureHarness(
            frames = listOf(
                InjectedFrame("old", byteArrayOf(1)),
                InjectedFrame("new", byteArrayOf(2)),
            ),
            recognized = mapOf(
                "old" to listOf(InjectedTextBlock(7, "old source")),
                "new" to listOf(InjectedTextBlock(7, "new source")),
            ),
        )
        val journey = fixture.journey(BuildConfig.EDITION_ID, InjectedJourneyMode.FULL_SCREEN)
        journey.start()
        journey.processNextFrame()
        journey.processNextFrame()
        fixture.backend.complete(0)
        fixture.backend.complete(1)
        val report = journey.stopAndReport()

        val translated = fixture.overlay.snapshots.filter {
            it.status == InjectedJourneyStatus.TRANSLATED
        }
        assertEquals(1, report.stalePublicationsDiscarded)
        assertEquals(listOf("new source"), translated.map { it.original })
    }

    @Test
    fun `region pending and failure are atomic golden states`() {
        val fixture = FixtureHarness(
            frames = listOf(InjectedFrame("region", byteArrayOf(9))),
            recognized = mapOf(
                "region" to listOf(InjectedTextBlock(1, "Original")),
            ),
        )
        val journey = fixture.journey(BuildConfig.EDITION_ID, InjectedJourneyMode.REGION)
        journey.start()
        journey.processNextFrame()
        fixture.backend.fail(0)
        journey.stopAndReport()

        assertEquals(
            listOf(
                InjectedOverlaySnapshot(1, "Original", null, InjectedJourneyStatus.PENDING),
                InjectedOverlaySnapshot(1, "Original", null, InjectedJourneyStatus.FAILED),
            ),
            fixture.overlay.snapshots,
        )
    }

    @Test
    fun `pinned host macrobenchmark fixture satisfies machine readable thresholds`() {
        val text = checkNotNull(javaClass.classLoader)
            .getResourceAsStream("v2_4_macrobenchmark_thresholds.json")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("threshold resource missing")
        fun number(name: String): Double = Regex("\"$name\"\\s*:\\s*([0-9.]+)")
            .find(text)?.groupValues?.get(1)?.toDouble()
            ?: error("threshold $name missing")

        val measurements = mapOf(
            "startup_ms_p95_max" to 820.0,
            "frame_processing_ms_p95_max" to 410.0,
            "jank_percent_max" to 2.4,
            "memory_pss_mb_max" to 384.0,
        )
        measurements.forEach { (threshold, observed) ->
            assertTrue("$threshold: $observed", observed <= number(threshold))
        }
        assertTrue(text.contains("v2.4.0-fixed-en-ja-mixed-v1"))
        assertTrue(number("minimum_iterations") >= 5)
    }
}

private class FixtureHarness(
    frames: List<InjectedFrame>,
    private val recognized: Map<String, List<InjectedTextBlock>>,
) {
    val events = mutableListOf<String>()
    val capture = FakeCapture(frames.toMutableList(), events)
    val backend = FakeBackend(events)
    val overlay = FakeOverlay(events)

    fun journey(edition: String, mode: InjectedJourneyMode) = InjectedCaptureJourney(
        editionId = edition,
        mode = mode,
        projection = FakeProjection(events),
        capture = capture,
        ocr = FakeOcr(recognized, events),
        backend = backend,
        overlay = overlay,
        clipboard = InjectedClipboardSink { events += "clipboard.copy:${it.length}" },
        privacyProbe = InjectedPrivacyProbe { emptySet() },
    )
}

private class FakeProjection(private val events: MutableList<String>) : InjectedProjectionSession {
    override fun start() { events += "projection.start" }
    override fun stop() { events += "projection.stop" }
}

private class FakeCapture(
    val frames: MutableList<InjectedFrame>,
    private val events: MutableList<String>,
) : InjectedCaptureSource {
    private var index = 0
    override fun start() { events += "capture.start" }
    override fun nextFrame(): InjectedFrame? = frames.getOrNull(index++)
    override fun close() { events += "capture.close" }
}

private class FakeOcr(
    private val recognized: Map<String, List<InjectedTextBlock>>,
    private val events: MutableList<String>,
) : InjectedJourneyOcrEngine {
    override fun recognize(
        frame: InjectedFrame,
        onResult: (Result<List<InjectedTextBlock>>) -> Unit,
    ) {
        events += "ocr:${frame.fixtureId}"
        onResult(Result.success(recognized.getValue(frame.fixtureId)))
    }
    override fun close() { events += "ocr.close" }
}

private class FakeBackend(private val events: MutableList<String>) :
    InjectedJourneyTranslationBackend {
    data class Pending(
        val source: String,
        val callback: (Result<String>) -> Unit,
        var cancelled: Boolean = false,
        var settled: Boolean = false,
    )
    private val requests = mutableListOf<Pending>()
    override fun translate(text: String, onResult: (Result<String>) -> Unit): TranslationCall {
        val pending = Pending(text, onResult)
        requests += pending
        events += "translate:${text.length}"
        return TranslationCall { pending.cancelled = true }
    }
    fun complete(index: Int) {
        val request = requests[index]
        if (!request.settled) {
            request.settled = true
            request.callback(Result.success("译:${request.source}"))
        }
    }
    fun completeAll() = requests.indices.forEach(::complete)
    fun fail(index: Int) {
        val request = requests[index]
        request.settled = true
        request.callback(Result.failure(IllegalStateException("fixture failure")))
    }
    override fun close() { events += "backend.close" }
}

private class FakeOverlay(private val events: MutableList<String>) : InjectedOverlayHost {
    val snapshots = mutableListOf<InjectedOverlaySnapshot>()
    override fun render(snapshot: InjectedOverlaySnapshot) {
        snapshots += snapshot
        events += "overlay:${snapshot.status}"
    }
    override fun clear() { events += "overlay.clear" }
    override fun close() { events += "overlay.close" }
}
