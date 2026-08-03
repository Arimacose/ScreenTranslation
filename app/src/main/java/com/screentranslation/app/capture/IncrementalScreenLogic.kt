package com.screentranslation.app.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersectionOverUnion(other: NormalizedBounds): Float {
        val intersectionWidth = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight =
            (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection == 0f) return 0f
        val area = (right - left) * (bottom - top)
        val otherArea = (other.right - other.left) * (other.bottom - other.top)
        return intersection / (area + otherArea - intersection)
    }
}

data class ScreenTextBlock(
    val text: String,
    val bounds: NormalizedBounds,
    val confidence: Float,
)

data class TrackedScreenTextBlock(
    val id: Long,
    val text: String,
    val bounds: NormalizedBounds,
    val confidence: Float,
    val isStable: Boolean,
)

data class TranslationPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
)

/** Resolves a label above its source box, falling below only when top space is insufficient. */
fun resolveTranslationPlacement(
    bounds: NormalizedBounds,
    screenWidth: Int,
    screenHeight: Int,
    labelHeight: Int,
    minimumWidth: Int,
    maximumWidth: Int,
    gap: Int,
): TranslationPlacement {
    require(screenWidth > 0 && screenHeight > 0)
    require(labelHeight >= 0 && minimumWidth > 0 && maximumWidth > 0 && gap >= 0)
    val sourceLeft = (bounds.left * screenWidth).toInt()
    val sourceTop = (bounds.top * screenHeight).toInt()
    val sourceRight = (bounds.right * screenWidth).toInt()
    val sourceBottom = (bounds.bottom * screenHeight).toInt()
    val width = max(sourceRight - sourceLeft, minimumWidth)
        .coerceAtMost(maximumWidth.coerceAtMost(screenWidth))
    val left = sourceLeft.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
    val above = sourceTop - labelHeight - gap
    val top = if (above >= 0) {
        above
    } else {
        (sourceBottom + gap).coerceAtMost((screenHeight - labelHeight).coerceAtLeast(0))
    }
    return TranslationPlacement(left = left, top = top, width = width)
}

/** Maintains block identity and requires two matching OCR observations. */
class IncrementalBlockTracker(
    private val stableObservations: Int = 2,
) {
    private data class State(
        val id: Long,
        val text: String,
        val bounds: NormalizedBounds,
        val confidence: Float,
        val observations: Int,
    )

    private var nextId = 1L
    private var previous = emptyList<State>()

    init {
        require(stableObservations > 0)
    }

    fun update(rawBlocks: List<ScreenTextBlock>): List<TrackedScreenTextBlock> {
        val blocks = deduplicate(rawBlocks)
        val unmatched = previous.toMutableSet()
        val next = blocks.map { block ->
            val match = unmatched.maxByOrNull { candidate -> matchScore(candidate, block) }
                ?.takeIf { matchScore(it, block) >= MIN_MATCH_SCORE }
            if (match == null) {
                State(nextId++, block.text, block.bounds, block.confidence, 1)
            } else {
                unmatched.remove(match)
                State(
                    id = match.id,
                    text = block.text,
                    bounds = block.bounds,
                    confidence = block.confidence,
                    observations = if (match.text == block.text) match.observations + 1 else 1,
                )
            }
        }
        previous = next
        return next.map { state ->
            TrackedScreenTextBlock(
                id = state.id,
                text = state.text,
                bounds = state.bounds,
                confidence = state.confidence,
                isStable = state.observations >= stableObservations,
            )
        }
    }

    fun reset() {
        previous = emptyList()
    }

    private fun deduplicate(blocks: List<ScreenTextBlock>): List<ScreenTextBlock> {
        val result = mutableListOf<ScreenTextBlock>()
        blocks.sortedByDescending { it.confidence }.forEach { candidate ->
            if (
                result.none {
                    it.text == candidate.text &&
                        it.bounds.intersectionOverUnion(candidate.bounds) >= DUPLICATE_IOU
                }
            ) {
                result += candidate
            }
        }
        return result.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun matchScore(previous: State, current: ScreenTextBlock): Float {
        val iou = previous.bounds.intersectionOverUnion(current.bounds)
        val centerDistance = abs(previous.bounds.centerX - current.bounds.centerX) +
            abs(previous.bounds.centerY - current.bounds.centerY)
        val geometry = max(iou, (1f - centerDistance * 3f).coerceIn(0f, 1f))
        val text = normalizedTextSimilarity(previous.text, current.text)
        return when {
            previous.text == current.text -> 0.65f + (geometry * 0.35f)
            iou >= 0.25f -> (text * 0.55f) + (geometry * 0.45f)
            else -> text * geometry
        }
    }

    private fun normalizedTextSimilarity(left: String, right: String): Float {
        if (left == right) return 1f
        if (left.isEmpty() || right.isEmpty()) return 0f
        val previousRow = IntArray(right.length + 1) { it }
        val currentRow = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            currentRow[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                currentRow[rightIndex + 1] = minOf(
                    currentRow[rightIndex] + 1,
                    previousRow[rightIndex + 1] + 1,
                    previousRow[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            currentRow.copyInto(previousRow)
        }
        return 1f - previousRow[right.length].toFloat() / max(left.length, right.length)
    }

    private companion object {
        const val MIN_MATCH_SCORE = 0.52f
        const val DUPLICATE_IOU = 0.55f
    }
}

data class PixelTile(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

object ScreenTileGrid {
    fun create(width: Int, height: Int, columns: Int = 3, rows: Int = 6): List<PixelTile> {
        require(width > 0 && height > 0 && columns > 0 && rows > 0)
        return buildList(columns * rows) {
            var index = 0
            repeat(rows) { row ->
                repeat(columns) { column ->
                    add(
                        PixelTile(
                            index = index++,
                            left = width * column / columns,
                            top = height * row / rows,
                            right = width * (column + 1) / columns,
                            bottom = height * (row + 1) / rows,
                        ),
                    )
                }
            }
        }
    }

    fun indexForNormalizedPoint(
        x: Float,
        y: Float,
        columns: Int = 3,
        rows: Int = 6,
    ): Int {
        require(x in 0f..1f && y in 0f..1f && columns > 0 && rows > 0)
        val column = (x * columns).toInt().coerceAtMost(columns - 1)
        val row = (y * rows).toInt().coerceAtMost(rows - 1)
        return row * columns + column
    }
}

fun verificationTileIndices(
    naturallyChanged: Set<Int>,
    blocks: List<TrackedScreenTextBlock>,
): Set<Int> = naturallyChanged + blocks.asSequence()
    .filterNot { it.isStable }
    .map { block ->
        ScreenTileGrid.indexForNormalizedPoint(block.bounds.centerX, block.bounds.centerY)
    }
    .toSet()

data class TileChangeSet(
    val natural: Set<Int>,
    val all: Set<Int>,
)

class TileSignatureDiffer {
    private var previous: List<IntArray>? = null

    fun compare(current: List<IntArray>, forced: Set<Int> = emptySet()): TileChangeSet {
        val old = previous
        val natural = if (old == null || old.size != current.size) {
            current.indices.toSet()
        } else {
            current.indices.filterTo(linkedSetOf()) { index ->
                signaturesDiffer(old[index], current[index])
            }
        }
        previous = current.map { it.clone() }
        return TileChangeSet(natural = natural, all = natural + forced)
    }

    fun reset() {
        previous = null
    }

    internal fun signaturesDiffer(previous: IntArray, current: IntArray): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return true
        var totalDifference = 0L
        var significant = 0
        previous.indices.forEach { index ->
            val difference = abs(previous[index] - current[index])
            totalDifference += difference
            if (difference >= SIGNIFICANT_LUMA_DELTA) significant += 1
        }
        val meanDifference = totalDifference.toFloat() / previous.size
        val significantFraction = significant.toFloat() / previous.size
        return meanDifference >= MEAN_LUMA_DELTA ||
            significantFraction >= SIGNIFICANT_PIXEL_FRACTION
    }

    private companion object {
        const val SIGNIFICANT_LUMA_DELTA = 24
        const val MEAN_LUMA_DELTA = 4f
        const val SIGNIFICANT_PIXEL_FRACTION = 0.008f
    }
}

class AdaptiveFrameInterval(
    private val activeIntervalMs: Long,
    private val maximumIntervalMs: Long = 2_000L,
    private val unchangedFramesPerStep: Int = 3,
) {
    var currentIntervalMs: Long = activeIntervalMs
        private set
    private var unchangedFrames = 0

    init {
        require(activeIntervalMs >= 0L)
        require(maximumIntervalMs >= activeIntervalMs)
        require(unchangedFramesPerStep > 0)
    }

    fun recordChanged(changed: Boolean): Long {
        if (changed) {
            unchangedFrames = 0
            currentIntervalMs = activeIntervalMs
        } else {
            unchangedFrames += 1
            val multiplier = 1L shl (unchangedFrames / unchangedFramesPerStep).coerceAtMost(4)
            currentIntervalMs = (activeIntervalMs * multiplier)
                .coerceAtMost(maximumIntervalMs)
        }
        return currentIntervalMs
    }

    fun reset() {
        unchangedFrames = 0
        currentIntervalMs = activeIntervalMs
    }
}

fun mapTileRegionToScreen(
    tileCrop: PixelTile,
    frameWidth: Int,
    frameHeight: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): NormalizedBounds = NormalizedBounds(
    left = (tileCrop.left + left * tileCrop.width) / frameWidth,
    top = (tileCrop.top + top * tileCrop.height) / frameHeight,
    right = (tileCrop.left + right * tileCrop.width) / frameWidth,
    bottom = (tileCrop.top + bottom * tileCrop.height) / frameHeight,
)
