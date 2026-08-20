package com.screentranslation.app.ml

import kotlin.math.max
import kotlin.math.min

enum class OcrProfileId(val persistedValue: String) {
    BALANCED("balanced"),
    SMALL_SUBTITLE("small_subtitle"),
    DOCUMENT("document");

    companion object {
        fun fromPersisted(value: String?): OcrProfileId = entries.firstOrNull {
            it.persistedValue == value
        } ?: BALANCED
    }
}

data class SecondPassBudget(
    val maxTiles: Int,
    val maxPixels: Long,
    val timeoutMillis: Long,
    val upscaleFactor: Float,
) {
    init {
        require(maxTiles in 1..8)
        require(maxPixels in 1..8_000_000)
        require(timeoutMillis in 100L..5_000L)
        require(upscaleFactor in 1f..2f)
    }
}

data class OcrProfile(
    val id: OcrProfileId,
    val detectionLongSide: Int,
    val recognitionThreshold: Float,
    val minimumBoxHeightPxAtReference: Int,
    val stabilityObservations: Int,
    val debounceMillis: Long,
    val secondPass: SecondPassBudget?,
) {
    init {
        require(detectionLongSide in 320..1_600)
        require(recognitionThreshold in 0f..1f)
        require(minimumBoxHeightPxAtReference in 1..96)
        require(stabilityObservations in 1..4)
        require(debounceMillis in 0L..5_000L)
    }
}

data class OcrRequest(
    val profile: OcrProfile,
    val passIndex: Int = 1,
    val roiIdentity: String? = null,
) {
    init {
        require(passIndex in 1..2)
        require(roiIdentity == null || roiIdentity.length <= 96)
    }
}

object OcrProfiles {
    val BALANCED = OcrProfile(
        id = OcrProfileId.BALANCED,
        detectionLongSide = 640,
        recognitionThreshold = 0.25f,
        minimumBoxHeightPxAtReference = 12,
        stabilityObservations = 2,
        debounceMillis = 750L,
        secondPass = null,
    )
    val SMALL_SUBTITLE = OcrProfile(
        id = OcrProfileId.SMALL_SUBTITLE,
        detectionLongSide = 960,
        recognitionThreshold = 0.22f,
        minimumBoxHeightPxAtReference = 22,
        stabilityObservations = 2,
        debounceMillis = 500L,
        secondPass = SecondPassBudget(
            maxTiles = 2,
            maxPixels = 1_500_000L,
            timeoutMillis = 900L,
            upscaleFactor = 1.5f,
        ),
    )
    val DOCUMENT = OcrProfile(
        id = OcrProfileId.DOCUMENT,
        detectionLongSide = 1_280,
        recognitionThreshold = 0.25f,
        minimumBoxHeightPxAtReference = 16,
        stabilityObservations = 3,
        debounceMillis = 1_000L,
        secondPass = SecondPassBudget(
            maxTiles = 4,
            maxPixels = 3_000_000L,
            timeoutMillis = 1_400L,
            upscaleFactor = 1.25f,
        ),
    )

    fun forId(id: OcrProfileId): OcrProfile = when (id) {
        OcrProfileId.BALANCED -> BALANCED
        OcrProfileId.SMALL_SUBTITLE -> SMALL_SUBTITLE
        OcrProfileId.DOCUMENT -> DOCUMENT
    }
}

internal object OcrSecondPassPolicy {
    fun shouldRun(
        request: OcrRequest,
        recognition: OcrEngine.Recognition,
        width: Int,
        height: Int,
        selectedTileCount: Int = 1,
    ): Boolean {
        val budget = request.profile.secondPass ?: return false
        if (request.passIndex != 1 || selectedTileCount !in 1..budget.maxTiles) return false
        val scaledPixels = width.toLong() * height.toLong() *
            budget.upscaleFactor * budget.upscaleFactor
        if (scaledPixels > budget.maxPixels) return false
        if (recognition.regions.isEmpty()) return true

        return recognition.regions.any { region ->
            val boxHeight = ((region.bottom - region.top).coerceAtLeast(0f) * height)
            boxHeight < request.profile.minimumBoxHeightPxAtReference &&
                region.confidence in request.profile.recognitionThreshold..RECOVERABLE_CONFIDENCE_CEILING
        }
    }

    fun merge(
        first: OcrEngine.Recognition,
        second: OcrEngine.Recognition,
    ): OcrEngine.Recognition {
        val retained = first.regions.toMutableList()
        second.regions.forEach { candidate ->
            val duplicateIndex = retained.indexOfFirst { existing ->
                normalizedText(existing.text) == normalizedText(candidate.text) ||
                    intersectionOverUnion(existing, candidate) >= DUPLICATE_IOU
            }
            if (duplicateIndex < 0) {
                retained += candidate
            } else if (
                candidate.confidence > retained[duplicateIndex].confidence ||
                candidate.text.trim().length > retained[duplicateIndex].text.trim().length
            ) {
                retained[duplicateIndex] = candidate
            }
        }
        val ordered = retained.sortedWith(compareBy(OcrEngine.TextRegion::top, OcrEngine.TextRegion::left))
        val lines = ordered.map { it.text.trim() }.filter(String::isNotEmpty)
        return OcrEngine.Recognition(
            text = lines.joinToString("\n"),
            blocks = lines,
            regions = ordered,
        )
    }

    private fun intersectionOverUnion(
        first: OcrEngine.TextRegion,
        second: OcrEngine.TextRegion,
    ): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val firstArea = (first.right - first.left).coerceAtLeast(0f) *
            (first.bottom - first.top).coerceAtLeast(0f)
        val secondArea = (second.right - second.left).coerceAtLeast(0f) *
            (second.bottom - second.top).coerceAtLeast(0f)
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun normalizedText(value: String): String = value
        .lowercase()
        .filterNot(Char::isWhitespace)

    private const val RECOVERABLE_CONFIDENCE_CEILING = 0.65f
    private const val DUPLICATE_IOU = 0.55f
}
