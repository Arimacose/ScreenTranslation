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
    private val maximumMissedObservations: Int = 2,
) {
    private data class State(
        val id: Long,
        val text: String,
        val bounds: NormalizedBounds,
        val confidence: Float,
        val observations: Int,
        val pendingText: String? = null,
        val pendingObservations: Int = 0,
        val missedObservations: Int = 0,
    )

    private var nextId = 1L
    private var previous = emptyList<State>()

    init {
        require(stableObservations > 0)
        require(maximumMissedObservations >= 0)
    }

    fun update(
        rawBlocks: List<ScreenTextBlock>,
        invalidatedTiles: Set<Int> = emptySet(),
    ): List<TrackedScreenTextBlock> {
        val degradedObservationIds = linkedSetOf<Long>()
        val blocks = mergeTileBoundaryFragments(deduplicate(rawBlocks)).filterNot { block ->
            previous.firstOrNull { state ->
                state.observations >= stableObservations &&
                    !wasCommittedTileInvalidated(state.bounds, invalidatedTiles) &&
                    isUntrustedStaticObservation(state, block)
            }?.also { degradedObservationIds += it.id } != null
        }
        val unmatched = previous.toMutableSet()
        val observed = blocks.map { block ->
            val match = unmatched.maxByOrNull { candidate -> matchScore(candidate, block) }
                ?.takeIf { matchScore(it, block) >= MIN_MATCH_SCORE }
            if (match == null) {
                State(nextId++, block.text, block.bounds, block.confidence, 1)
            } else {
                unmatched.remove(match)
                updateMatchedState(match, block, invalidatedTiles)
            }
        }
        val retained = unmatched.mapNotNull { state ->
            if (state.id in degradedObservationIds) {
                // OCR saw only a smaller piece inside a known complete line.
                // Treat that as a successful observation of the committed
                // source instead of aging it out or creating a new fragment.
                return@mapNotNull state.copy(
                    pendingText = null,
                    pendingObservations = 0,
                    missedObservations = 0,
                )
            }
            val tile = ScreenTileGrid.indexForNormalizedPoint(
                state.bounds.centerX,
                state.bounds.centerY,
            )
            if (tile in invalidatedTiles || state.missedObservations >= maximumMissedObservations) {
                null
            } else {
                state.copy(missedObservations = state.missedObservations + 1)
            }
        }
        val next = (observed + retained).sortedWith(
            compareBy({ it.bounds.top }, { it.bounds.left }),
        )
        previous = next
        return next.map { state ->
            TrackedScreenTextBlock(
                id = state.id,
                text = state.text,
                bounds = state.bounds,
                confidence = state.confidence,
                isStable = state.observations >= stableObservations &&
                    state.pendingText == null &&
                    state.missedObservations == 0,
            )
        }
    }

    private fun updateMatchedState(
        previous: State,
        current: ScreenTextBlock,
        invalidatedTiles: Set<Int>,
    ): State {
        val previousTile = ScreenTileGrid.indexForNormalizedPoint(
            previous.bounds.centerX,
            previous.bounds.centerY,
        )
        val committedTileWasInvalidated = previousTile in invalidatedTiles

        if (previous.text == current.text) {
            // Forced verification runs can return the same committed text with
            // a much narrower box from one overlapping OCR tile. Moving the
            // label to that transient box can cover the source line and turn
            // the next pass into progressively smaller fragments. Keep the
            // committed geometry unless the underlying screen tile actually
            // changed; a real move/change is then free to adopt fresh bounds.
            val keepCommittedBounds = previous.observations >= stableObservations &&
                !committedTileWasInvalidated
            return State(
                id = previous.id,
                text = current.text,
                bounds = if (keepCommittedBounds) previous.bounds else current.bounds,
                confidence = if (keepCommittedBounds) {
                    max(previous.confidence, current.confidence)
                } else {
                    current.confidence
                },
                observations = previous.observations + 1,
            )
        }

        if (previous.observations >= stableObservations && !committedTileWasInvalidated) {
            // An expanded crop from a dirty neighboring tile can contain a
            // corrupted copy of this line while the committed line's own tile
            // is unchanged. Preserve the complete source and translation.
            return previous.copy(
                pendingText = null,
                pendingObservations = 0,
                missedObservations = 0,
            )
        }

        if (committedTileWasInvalidated || previous.observations < stableObservations) {
            // The screen really changed, or the old candidate was never stable:
            // adopt the new text immediately but require a confirming pass.
            return State(
                id = previous.id,
                text = current.text,
                bounds = current.bounds,
                confidence = current.confidence,
                observations = 1,
            )
        }

        val pendingCount = if (previous.pendingText == current.text) {
            previous.pendingObservations + 1
        } else {
            1
        }
        return if (pendingCount >= stableObservations) {
            State(
                id = previous.id,
                text = current.text,
                bounds = current.bounds,
                confidence = current.confidence,
                observations = stableObservations,
            )
        } else {
            // A single OCR fluctuation must not evict or reposition a
            // known-good label. Keep publishing both the committed text and
            // committed geometry while forcing another observation.
            previous.copy(
                pendingText = current.text,
                pendingObservations = pendingCount,
                missedObservations = 0,
            )
        }
    }

    fun reset() {
        previous = emptyList()
    }

    private fun deduplicate(blocks: List<ScreenTextBlock>): List<ScreenTextBlock> {
        val result = mutableListOf<ScreenTextBlock>()
        blocks.sortedWith(
            compareByDescending<ScreenTextBlock> { normalizedDuplicateText(it.text).length }
                .thenByDescending { it.confidence },
        ).forEach { candidate ->
            if (result.none { retained -> duplicateBlocks(retained, candidate) }) {
                result += candidate
            }
        }
        return result.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun duplicateBlocks(left: ScreenTextBlock, right: ScreenTextBlock): Boolean {
        val leftText = normalizedDuplicateText(left.text)
        val rightText = normalizedDuplicateText(right.text)
        if (leftText == rightText) {
            return left.bounds.intersectionOverUnion(right.bounds) >= DUPLICATE_IOU
        }

        val shorter = if (leftText.length <= rightText.length) leftText else rightText
        val longer = if (leftText.length > rightText.length) leftText else rightText
        if (shorter.length < MIN_CONTAINED_TEXT_LENGTH ||
            shorter.length.toFloat() / longer.length < MIN_CONTAINED_TEXT_FRACTION ||
            !longer.contains(shorter)
        ) {
            return false
        }

        return axisOverlapOverSmaller(
            left.bounds.top,
            left.bounds.bottom,
            right.bounds.top,
            right.bounds.bottom,
        ) >= MIN_DUPLICATE_VERTICAL_OVERLAP &&
            axisOverlapOverSmaller(
                left.bounds.left,
                left.bounds.right,
                right.bounds.left,
                right.bounds.right,
            ) >= MIN_DUPLICATE_HORIZONTAL_OVERLAP
    }

    /**
     * Expanded tile OCR can return complementary pieces of one long line on
     * opposite sides of a 1/3 or 2/3 column boundary. Reconstruct that line
     * before identity/stability tracking so it produces one translation label.
     * Geometry is deliberately strict to avoid joining unrelated same-row UI
     * controls.
     */
    private fun mergeTileBoundaryFragments(
        blocks: List<ScreenTextBlock>,
    ): List<ScreenTextBlock> {
        val working = blocks.toMutableList()
        var merged: Boolean
        do {
            merged = false
            outer@ for (leftIndex in working.indices) {
                for (rightIndex in leftIndex + 1 until working.size) {
                    val first = working[leftIndex]
                    val second = working[rightIndex]
                    val left = if (first.bounds.centerX <= second.bounds.centerX) first else second
                    val right = if (left === first) second else first
                    if (!areTileBoundaryFragments(left, right)) continue

                    working[leftIndex] = mergeFragments(left, right)
                    working.removeAt(rightIndex)
                    merged = true
                    break@outer
                }
            }
        } while (merged)

        return working.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun areTileBoundaryFragments(
        left: ScreenTextBlock,
        right: ScreenTextBlock,
    ): Boolean {
        if (axisOverlapOverSmaller(
                left.bounds.top,
                left.bounds.bottom,
                right.bounds.top,
                right.bounds.bottom,
            ) < MIN_FRAGMENT_VERTICAL_OVERLAP
        ) {
            return false
        }

        val smallerHeight = min(
            left.bounds.bottom - left.bounds.top,
            right.bounds.bottom - right.bounds.top,
        )
        val horizontalGap = (right.bounds.left - left.bounds.right).coerceAtLeast(0f)
        if (horizontalGap > max(MAX_FRAGMENT_BOUNDARY_OFFSET, smallerHeight * 1.5f)) {
            return false
        }

        return TILE_COLUMN_BOUNDARIES.any { boundary ->
            left.bounds.centerX < boundary &&
                right.bounds.centerX >= boundary &&
                left.bounds.right >= boundary - MAX_FRAGMENT_BOUNDARY_OFFSET &&
                right.bounds.left <= boundary + MAX_FRAGMENT_BOUNDARY_OFFSET
        }
    }

    private fun mergeFragments(
        left: ScreenTextBlock,
        right: ScreenTextBlock,
    ): ScreenTextBlock = ScreenTextBlock(
        text = mergeFragmentText(left.text, right.text),
        bounds = NormalizedBounds(
            left = min(left.bounds.left, right.bounds.left),
            top = min(left.bounds.top, right.bounds.top),
            right = max(left.bounds.right, right.bounds.right),
            bottom = max(left.bounds.bottom, right.bounds.bottom),
        ),
        confidence = max(left.confidence, right.confidence),
    )

    private fun mergeFragmentText(left: String, right: String): String {
        val leftText = left.trim()
        val rightText = right.trim()
        val leftNormalized = normalizedDuplicateText(leftText)
        val rightNormalized = normalizedDuplicateText(rightText)
        if (leftNormalized.contains(rightNormalized)) return leftText
        if (rightNormalized.contains(leftNormalized)) return rightText

        val overlap = sharedSuffixPrefixLength(leftText, rightText)
        if (overlap > 0) return leftText + rightText.substring(overlap)

        val separator = if (continuesProtectedToken(leftText) ||
            rightText.firstOrNull() in NO_LEADING_SPACE_PUNCTUATION
        ) {
            ""
        } else {
            " "
        }
        return leftText + separator + rightText
    }

    private fun sharedSuffixPrefixLength(left: String, right: String): Int {
        val maximum = min(left.length, right.length)
        for (length in maximum downTo MIN_FRAGMENT_TEXT_OVERLAP) {
            if (left.regionMatches(
                    thisOffset = left.length - length,
                    other = right,
                    otherOffset = 0,
                    length = length,
                    ignoreCase = true,
                )
            ) {
                return length
            }
        }
        return 0
    }

    private fun continuesProtectedToken(left: String): Boolean {
        val token = left.substringAfterLast(' ')
        return token.lastOrNull() in NO_TRAILING_SPACE_PUNCTUATION ||
            (token.contains('@') && '.' !in token.substringAfter('@')) ||
            token.startsWith("http://", ignoreCase = true) ||
            token.startsWith("https://", ignoreCase = true) ||
            token.startsWith("www.", ignoreCase = true)
    }

    private fun axisOverlapOverSmaller(
        leftStart: Float,
        leftEnd: Float,
        rightStart: Float,
        rightEnd: Float,
    ): Float {
        val overlap = (min(leftEnd, rightEnd) - max(leftStart, rightStart)).coerceAtLeast(0f)
        val smaller = min(leftEnd - leftStart, rightEnd - rightStart)
        return if (smaller <= 0f) 0f else overlap / smaller
    }

    private fun normalizedDuplicateText(text: String): String = text
        .trim()
        .lowercase()
        .replace(DUPLICATE_WHITESPACE, " ")

    private fun wasCommittedTileInvalidated(
        previous: NormalizedBounds,
        invalidatedTiles: Set<Int>,
    ): Boolean = ScreenTileGrid.indexForNormalizedPoint(
        previous.centerX,
        previous.centerY,
    ) in invalidatedTiles

    private fun isUntrustedStaticObservation(
        previous: State,
        current: ScreenTextBlock,
    ): Boolean {
        if (previous.text == current.text) return false
        return matchScore(previous, current) >= MIN_MATCH_SCORE ||
            isDegradedFragment(previous, current)
    }

    /**
     * A static full line must not be downgraded to a short OCR fragment merely
     * because an overlay temporarily hid part of it. Real content changes are
     * handled before this check through [invalidatedTiles].
     */
    private fun isDegradedFragment(previous: State, current: ScreenTextBlock): Boolean {
        val previousText = normalizedDuplicateText(previous.text)
        val currentText = normalizedDuplicateText(current.text)
        if (previousText.length < MIN_COMMITTED_TEXT_LENGTH ||
            currentText.length >= previousText.length * MAX_FRAGMENT_TEXT_FRACTION
        ) {
            return false
        }
        val previousWidth = previous.bounds.right - previous.bounds.left
        val currentWidth = current.bounds.right - current.bounds.left
        if (currentWidth >= previousWidth * MAX_FRAGMENT_WIDTH_FRACTION) return false

        return axisOverlapOverSmaller(
            previous.bounds.top,
            previous.bounds.bottom,
            current.bounds.top,
            current.bounds.bottom,
        ) >= MIN_FRAGMENT_VERTICAL_OVERLAP &&
            axisOverlapOverSmaller(
                previous.bounds.left,
                previous.bounds.right,
                current.bounds.left,
                current.bounds.right,
            ) >= MIN_DEGRADED_FRAGMENT_HORIZONTAL_OVERLAP
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
        const val MIN_CONTAINED_TEXT_LENGTH = 6
        const val MIN_CONTAINED_TEXT_FRACTION = 0.35f
        const val MIN_DUPLICATE_VERTICAL_OVERLAP = 0.60f
        const val MIN_DUPLICATE_HORIZONTAL_OVERLAP = 0.15f
        const val MIN_FRAGMENT_VERTICAL_OVERLAP = 0.60f
        const val MAX_FRAGMENT_BOUNDARY_OFFSET = 0.055f
        const val MIN_FRAGMENT_TEXT_OVERLAP = 2
        const val MIN_COMMITTED_TEXT_LENGTH = 12
        const val MAX_FRAGMENT_TEXT_FRACTION = 0.80f
        const val MAX_FRAGMENT_WIDTH_FRACTION = 0.88f
        const val MIN_DEGRADED_FRAGMENT_HORIZONTAL_OVERLAP = 0.70f
        val DUPLICATE_WHITESPACE = Regex("\\s+")
        val TILE_COLUMN_BOUNDARIES = listOf(1f / 3f, 2f / 3f)
        val NO_TRAILING_SPACE_PUNCTUATION = setOf('@', '/', '.', '_', '-')
        val NO_LEADING_SPACE_PUNCTUATION = setOf('.', ',', '!', '?', ':', ';', ')', ']', '}')
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

/** Removes labels whose source tile changed before the next OCR result is ready. */
fun blocksOutsideTiles(
    blocks: List<TrackedScreenTextBlock>,
    changedTiles: Set<Int>,
): List<TrackedScreenTextBlock> = if (changedTiles.isEmpty()) {
    blocks
} else {
    blocks.filterNot { block ->
        ScreenTileGrid.indexForNormalizedPoint(block.bounds.centerX, block.bounds.centerY) in
            changedTiles
    }
}

data class TileChangeSet(
    val natural: Set<Int>,
    val all: Set<Int>,
)

class TileSignatureDiffer {
    private var previous: List<IntArray>? = null

    val hasBaseline: Boolean
        get() = previous != null

    fun compare(
        current: List<IntArray>,
        forced: Set<Int> = emptySet(),
        suppressedNaturalTiles: Set<Int> = emptySet(),
    ): TileChangeSet {
        val old = previous
        val detectedNatural = if (old == null || old.size != current.size) {
            current.indices.toSet()
        } else {
            current.indices.filterTo(linkedSetOf()) { index ->
                signaturesDiffer(old[index], current[index])
            }
        }
        previous = current.map { it.clone() }
        val natural = detectedNatural - suppressedNaturalTiles
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
