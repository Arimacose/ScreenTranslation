package com.screentranslation.app.journey

import com.screentranslation.app.ml.TranslationCall
import java.security.MessageDigest
import java.util.concurrent.CancellationException

enum class InjectedJourneyMode { REGION, FULL_SCREEN }
enum class InjectedJourneyStatus { PENDING, TRANSLATED, FAILED }

data class InjectedFrame(val fixtureId: String, val pixels: ByteArray)
data class InjectedTextBlock(val id: Long, val text: String)
data class InjectedOverlaySnapshot(
    val blockId: Long,
    val original: String,
    val translation: String?,
    val status: InjectedJourneyStatus,
)

interface InjectedCaptureSource : AutoCloseable {
    fun start()
    fun nextFrame(): InjectedFrame?
}

interface InjectedProjectionSession : AutoCloseable {
    fun start()
    fun stop()
    override fun close() = stop()
}

interface InjectedJourneyOcrEngine : AutoCloseable {
    fun recognize(frame: InjectedFrame, onResult: (Result<List<InjectedTextBlock>>) -> Unit)
}

interface InjectedJourneyTranslationBackend : AutoCloseable {
    fun translate(text: String, onResult: (Result<String>) -> Unit): TranslationCall
}

interface InjectedOverlayHost : AutoCloseable {
    fun render(snapshot: InjectedOverlaySnapshot)
    fun clear()
}

fun interface InjectedClipboardSink {
    fun copy(text: String)
}

fun interface InjectedPrivacyProbe {
    /** Returns artifact categories, never screenshot or recognized-text content. */
    fun findPersistedCaptureArtifacts(): Set<String>
}

data class InjectedJourneyReport(
    val editionId: String,
    val mode: InjectedJourneyMode,
    val frames: Int,
    val recognizedBlocks: Int,
    val publishedBlocks: Int,
    val stalePublicationsDiscarded: Int,
    val copiedCharacters: Int,
    val persistedArtifactCategories: Set<String>,
    val releasedResources: Set<String>,
) {
    init {
        require(editionId in setOf("lite", "full", "online"))
        require(frames >= 0 && recognizedBlocks >= 0 && publishedBlocks >= 0)
        require(copiedCharacters >= 0)
    }
}

/**
 * Deterministic capture journey used by every edition's JVM and instrumentation gates.
 * Frames and recognized text live only in memory; reports retain counts and fixture hashes.
 */
class InjectedCaptureJourney(
    private val editionId: String,
    private val mode: InjectedJourneyMode,
    private val projection: InjectedProjectionSession,
    private val capture: InjectedCaptureSource,
    private val ocr: InjectedJourneyOcrEngine,
    private val backend: InjectedJourneyTranslationBackend,
    private val overlay: InjectedOverlayHost,
    private val clipboard: InjectedClipboardSink,
    private val privacyProbe: InjectedPrivacyProbe,
) : AutoCloseable {
    private data class SourceRevision(val digest: String, val generation: Long)

    private val revisions = linkedMapOf<Long, SourceRevision>()
    private val translations = linkedMapOf<Long, String>()
    private val activeCalls = linkedSetOf<TranslationCall>()
    private val released = linkedSetOf<String>()
    private var generation = 0L
    private var started = false
    private var closed = false
    private var frames = 0
    private var recognized = 0
    private var published = 0
    private var staleDiscarded = 0
    private var copiedCharacters = 0

    fun start() {
        check(!started && !closed)
        projection.start()
        capture.start()
        started = true
    }

    fun processNextFrame(): Boolean {
        check(started && !closed)
        val frame = capture.nextFrame() ?: return false
        frames += 1
        ocr.recognize(frame) { result ->
            result.fold(onSuccess = ::acceptRecognizedBlocks, onFailure = ::renderFailure)
        }
        frame.pixels.fill(0)
        return true
    }

    private fun acceptRecognizedBlocks(blocks: List<InjectedTextBlock>) {
        val normalized = if (mode == InjectedJourneyMode.REGION) blocks.take(1) else blocks
        recognized += normalized.size
        val activeIds = normalized.mapTo(linkedSetOf()) { it.id }
        revisions.keys.retainAll(activeIds)
        translations.keys.retainAll(activeIds)
        normalized.forEach { block ->
            if (block.text.isBlank()) return@forEach
            val revision = SourceRevision(digest(block.text), ++generation)
            revisions[block.id] = revision
            overlay.render(
                InjectedOverlaySnapshot(
                    block.id,
                    block.text,
                    translations[block.id],
                    InjectedJourneyStatus.PENDING,
                ),
            )
            var callRef: TranslationCall? = null
            var completedSynchronously = false
            val call = backend.translate(block.text) { result ->
                callRef?.let(activeCalls::remove) ?: run { completedSynchronously = true }
                val current = revisions[block.id]
                if (closed || current != revision) {
                    staleDiscarded += 1
                    return@translate
                }
                result.fold(
                    onSuccess = { translated ->
                        translations[block.id] = translated
                        published += 1
                        overlay.render(
                            InjectedOverlaySnapshot(
                                block.id,
                                block.text,
                                translated,
                                InjectedJourneyStatus.TRANSLATED,
                            ),
                        )
                    },
                    onFailure = {
                        if (it !is CancellationException) {
                            overlay.render(
                                InjectedOverlaySnapshot(
                                    block.id,
                                    block.text,
                                    translations[block.id],
                                    InjectedJourneyStatus.FAILED,
                                ),
                            )
                        }
                    },
                )
            }
            callRef = call
            if (!completedSynchronously) activeCalls += call
        }
    }

    private fun renderFailure(error: Throwable) {
        if (error is CancellationException) return
        revisions.keys.forEach { id ->
            overlay.render(
                InjectedOverlaySnapshot(id, "", translations[id], InjectedJourneyStatus.FAILED),
            )
        }
    }

    fun copyLatest(blockId: Long) {
        val translation = translations[blockId] ?: return
        clipboard.copy(translation)
        copiedCharacters += translation.length
    }

    fun stopAndReport(): InjectedJourneyReport {
        close()
        return InjectedJourneyReport(
            editionId,
            mode,
            frames,
            recognized,
            published,
            staleDiscarded,
            copiedCharacters,
            privacyProbe.findPersistedCaptureArtifacts(),
            released.toSet(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        activeCalls.toList().forEach(TranslationCall::cancel)
        activeCalls.clear()
        backend.close().also { released += "translation_backend" }
        ocr.close().also { released += "ocr_engine" }
        overlay.clear()
        overlay.close().also { released += "overlay_host" }
        capture.close().also { released += "capture_source" }
        projection.stop().also { released += "projection_session" }
        revisions.clear()
        translations.clear()
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
