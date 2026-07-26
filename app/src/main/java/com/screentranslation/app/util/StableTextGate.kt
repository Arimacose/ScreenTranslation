package com.screentranslation.app.util

import kotlin.math.max

/**
 * Suppresses transient OCR output and emits text only after it is observed in
 * [requiredConsecutiveMatches] sufficiently similar consecutive frames.
 *
 * A stable value is emitted once. Any textual change can pass after the new
 * value independently satisfies the consecutive-frame requirement.
 */
class StableTextGate(
    private val requiredConsecutiveMatches: Int = 2,
    private val similarityThreshold: Double = 0.90,
    private val minimumTextLength: Int = 2,
) {
    private var candidate: String? = null
    private var consecutiveMatches = 0
    private var lastEmitted: String? = null

    init {
        require(requiredConsecutiveMatches >= 1) {
            "requiredConsecutiveMatches must be at least 1"
        }
        require(similarityThreshold in 0.0..1.0) {
            "similarityThreshold must be between 0 and 1"
        }
        require(minimumTextLength >= 0) {
            "minimumTextLength cannot be negative"
        }
    }

    /**
     * Returns normalized stable text when a new value crosses the gate, or
     * `null` while the input is transient/unchanged.
     */
    @Synchronized
    fun offer(text: String): String? {
        val normalized = normalize(text)
        if (normalized.length < minimumTextLength) {
            candidate = null
            consecutiveMatches = 0
            return null
        }

        val previousEmission = lastEmitted
        if (previousEmission != null && normalized == previousEmission) {
            candidate = normalized
            consecutiveMatches = 0
            return null
        }

        val previousCandidate = candidate
        val candidateIsPendingChange =
            previousCandidate != null &&
                (previousEmission == null || previousCandidate != previousEmission)
        if (candidateIsPendingChange &&
            similarity(previousCandidate, normalized) >= similarityThreshold
        ) {
            candidate = normalized
            consecutiveMatches += 1
        } else {
            candidate = normalized
            consecutiveMatches = 1
        }

        if (consecutiveMatches < requiredConsecutiveMatches) {
            return null
        }

        lastEmitted = normalized
        consecutiveMatches = 0
        return normalized
    }

    @Synchronized
    fun reset() {
        candidate = null
        consecutiveMatches = 0
        lastEmitted = null
    }

    internal fun normalize(text: String): String =
        text
            .trim()
            .replace(WHITESPACE, " ")

    internal fun similarity(first: String, second: String): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0

        val longerLength = max(first.length, second.length)
        return 1.0 - levenshteinDistance(first, second).toDouble() / longerLength
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)

        for (firstIndex in first.indices) {
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                val substitutionCost = if (first[firstIndex] == second[secondIndex]) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
