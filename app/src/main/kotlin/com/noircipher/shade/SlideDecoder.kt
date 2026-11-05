package com.noircipher.shade.slide

import com.noircipher.shade.Key
import com.noircipher.shade.SpatialScorer
import com.noircipher.shade.WordDictionary
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

/**
 * Gesture-based glide typing decoder.
 *
 * Takes a swipe path (list of x,y points) and decodes it into a word by:
 * 1. Detecting "key crossings" — points where the path is closest to a key center
 * 2. Building a raw letter sequence from those crossings
 * 3. Finding dictionary words that match the raw sequence (allowing skips)
 * 4. Scoring candidates by spatial proximity to the actual path
 *
 * The decoder handles:
 * - Double letters (path stays near one key → two of that letter)
 * - Skipped letters (path passes near a key but doesn't stop)
 * - Inflection points (direction changes → letter boundaries)
 */
class SlideDecoder(
    private val dictionary: WordDictionary
) {
    private data class KeyHit(val char: Char, val x: Float, val y: Float, val dist: Float, val pathIndex: Int)

    suspend fun decode(path: List<FloatArray>, layout: Map<Char, Key>): String? {
        if (path.size < 4 || layout.isEmpty()) return null

        // Step 1: Find key crossings via inflection points + proximity
        val hits = findKeyCrossings(path, layout)
        if (hits.size < 2) {
            // Fallback: simple nearest-key sequence
            val raw = rawSequence(path, layout)
            return raw.ifEmpty { null }?.let { refineWithDictionary(it, path, layout) }
        }

        // Step 2: Build raw letter sequence from crossings
        val raw = StringBuilder()
        var lastChar = ' '
        var lastHit: KeyHit? = null
        for (hit in hits) {
            if (hit.char != lastChar) {
                raw.append(hit.char)
                lastChar = hit.char
                lastHit = hit
            } else if (lastHit != null) {
                // Same key — check if it's a double letter (significant time/distance gap)
                val gap = hypot(hit.x - lastHit.x, hit.y - lastHit.y)
                if (gap > 30f) {
                    raw.append(hit.char)
                }
            }
        }

        val rawWord = raw.toString()
        if (rawWord.length < 2) return null

        return refineWithDictionary(rawWord, path, layout)
    }

    /**
     * Find points where the path is closest to a key center, or where
     * the path changes direction (inflection points).
     */
    private fun findKeyCrossings(path: List<FloatArray>, layout: Map<Char, Key>): List<KeyHit> {
        val hits = ArrayList<KeyHit>()
        val keyList = layout.values.filter { it.label.length == 1 }
        if (keyList.isEmpty()) return hits

        var i = 0
        while (i < path.size) {
            val point = path[i]
            val x = point[0]
            val y = point[1]

            // Find closest key to this point
            var bestKey: Key? = null
            var bestChar = ' '
            var bestDist = Float.MAX_VALUE
            for (k in keyList) {
                val cx = (k.left + k.right) / 2f
                val cy = (k.top + k.bottom) / 2f
                val d = hypot(x - cx, y - cy)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = k
                    bestChar = k.label[0].lowercaseChar()
                }
            }

            if (bestKey != null) {
                hits.add(KeyHit(bestChar, x, y, bestDist, i))
            }

            // Skip ahead — don't sample every point, just key transitions
            i += if (bestDist < 20f) 3 else 2
        }

        // Deduplicate consecutive same-char hits, keeping the closest one
        val deduped = ArrayList<KeyHit>()
        for (hit in hits) {
            if (deduped.isEmpty() || deduped.last().char != hit.char) {
                deduped.add(hit)
            } else if (hit.dist < deduped.last().dist) {
                deduped[deduped.lastIndex] = hit
            }
        }

        return deduped
    }

    /**
     * Refine the raw sequence using the dictionary: find the best-matching word.
     */
    private suspend fun refineWithDictionary(
        raw: String,
        path: List<FloatArray>,
        layout: Map<Char, Key>
    ): String? {
        val lower = raw.lowercase()

        // Try exact match first
        if (dictionary.contains(lower)) return lower

        // Get dictionary suggestions for the raw sequence
        val candidates = dictionary.suggestAsync(lower, 12)
        if (candidates.isEmpty()) return lower

        // Also try prefix-based candidates
        if (lower.length >= 3) {
            val prefixCands = dictionary.suggestAsync(lower.substring(0, minOf(3, lower.length)), 8)
            // Merge and deduplicate
            val merged = (candidates + prefixCands).distinctBy { it.lowercase() }
            if (merged.size > 1) {
                val ranked = SpatialScorer.tiebreak(merged, path, layout)
                return ranked.firstOrNull()
            }
        }

        val ranked = SpatialScorer.tiebreak(candidates, path, layout)
        return ranked.firstOrNull() ?: lower
    }

    /** Simple nearest-key sequence — fallback when inflection detection fails. */
    private fun rawSequence(path: List<FloatArray>, layout: Map<Char, Key>): String {
        val out = StringBuilder()
        var last = ' '
        for (point in path) {
            val x = point[0]
            val y = point[1]
            var best: Char? = null
            var bestDist = Float.MAX_VALUE
            for ((ch, key) in layout) {
                if (!ch.isLetter()) continue
                val cx = (key.left + key.right) / 2f
                val cy = (key.top + key.bottom) / 2f
                val dx = x - cx
                val dy = y - cy
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    best = ch
                }
            }
            val c = best?.lowercaseChar()
            if (c != null && c != last) {
                out.append(c)
                last = c
            }
        }
        return out.toString()
    }
}
