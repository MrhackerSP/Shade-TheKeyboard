package com.noircipher.shade

import kotlin.math.exp

/**
 * Spatial Gaussian scorer. Re-weights autocorrect candidates by how close
 * the user's finger was to each candidate's letter positions.
 *
 * Uses per-axis sigma computed from the actual key width/height, so wide
 * keys (space, shift) tolerate more horizontal drift than narrow keys.
 * Vertical sigma is tighter than horizontal — vertical errors on a
 * touchscreen are almost always typos, while horizontal errors are
 * usually finger drift on the same row.
 *
 * This is a TIEBREAKER, not a primary signal. Running it on every
 * candidate for every keystroke adds latency without improving rank.
 */
object SpatialScorer {

    /** Half the key width — finger drift allowance on the horizontal axis. */
    private fun sigmaX(k: Key) = ((k.right - k.left) * 0.45f).coerceAtLeast(1f)

    /** Tighter vertical — vertical error is usually a real typo. */
    private fun sigmaY(k: Key) = ((k.bottom - k.top) * 0.35f).coerceAtLeast(1f)

    /**
     * Score a candidate word against the user's actual touch points.
     * Higher (less negative) is better.
     *
     * [touches] is a parallel list of (x, y) points recorded as the user
     * typed the word, one per committed character. [layout] maps each
     * character to its key bounds.
     */
    fun score(
        candidate: String,
        touches: List<FloatArray>,
        layout: Map<Char, Key>
    ): Float {
        if (candidate.isEmpty() || touches.isEmpty()) return 0f
        val n = minOf(candidate.length, touches.size)
        var total = 0f

        for (i in 0 until n) {
            val c = candidate[i].lowercaseChar()
            val k = layout[c] ?: continue
            val cx = (k.left + k.right) / 2f
            val cy = (k.top + k.bottom) / 2f
            val sx = sigmaX(k)
            val sy = sigmaY(k)
            val dx = touches[i][0] - cx
            val dy = touches[i][1] - cy
            total += -exp(
                -((dx * dx) / (2f * sx * sx) + (dy * dy) / (2f * sy * sy))
            )
        }
        return total / n
    }

    /**
     * Rank candidates that already tied on unigram + bigram score.
     * Only called when the caller has determined that two or more
     * candidates need to be disambiguated spatially.
     */
    fun tiebreak(
        candidates: List<String>,
        touches: List<FloatArray>,
        layout: Map<Char, Key>
    ): List<String> {
        if (layout.isEmpty() || touches.isEmpty() || candidates.size < 2) return candidates
        return candidates.sortedByDescending { score(it, touches, layout) }
    }
}
