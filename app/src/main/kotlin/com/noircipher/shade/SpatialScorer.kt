package com.noircipher.shade

import kotlin.math.exp
import kotlin.math.pow

/**
 * Spatial Gaussian scorer: reweights autocorrect candidates by how close
 * the user's finger was to each candidate's letter positions.
 * Adapted from SpectreBoard's approach.
 */
object SpatialScorer {

    // Finger-position error model (in key-widths)
    private const val SIGMA = 0.55f

    /**
     * Given a typed word and the key layout, score a candidate word by
     * summing the negative log-likelihood of each candidate letter being
     * produced by a finger near the typed letter's key.
     */
    fun score(typed: String, candidate: String, layout: Map<Char, KeyPos>): Float {
        if (typed.isEmpty() || candidate.isEmpty()) return 0f
        val n = maxOf(typed.length, candidate.length)
        var total = 0f

        for (i in 0 until n) {
            val tc = typed.getOrNull(i)?.lowercaseChar() ?: continue
            val cc = candidate.getOrNull(i)?.lowercaseChar() ?: continue
            val tp = layout[tc] ?: continue
            val cp = layout[cc] ?: continue
            val d2 = (tp.x - cp.x).pow(2) + (tp.y - cp.y).pow(2)
            total += -exp(-d2 / (2f * SIGMA * SIGMA))
        }
        return total / n
    }

    /**
     * Rerank candidates by combined dictionary score + spatial score.
     * Higher is better.
     */
    fun rerank(typed: String, candidates: List<String>, layout: Map<Char, KeyPos>): List<String> {
        if (layout.isEmpty()) return candidates
        return candidates.sortedByDescending { c ->
            // Dictionary rank is not passed here; spatial is a small tiebreaker.
            score(typed, c, layout)
        }
    }

    /** Normalized key-center coordinates in key-width units. */
    data class KeyPos(val x: Float, val y: Float)
}
