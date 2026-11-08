package com.noircipher.shade.slide

import com.noircipher.shade.Key
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
/** The slice of the dictionary the decoder needs (lets tests use a tiny fake). */
interface SlideDictionary {
    fun contains(word: String): Boolean
    suspend fun suggestAsync(prefix: String, max: Int): List<String>
}

class SlideDecoder(
    private val dictionary: SlideDictionary
) {
    constructor(dictionary: WordDictionary) : this(object : SlideDictionary {
        override fun contains(word: String) = dictionary.contains(word)
        override suspend fun suggestAsync(prefix: String, max: Int) = dictionary.suggestAsync(prefix, max)
    })

    private data class KeyHit(
        val char: Char, val x: Float, val y: Float, val dist: Float, val pathIndex: Int,
        /** The swipe looped / wiggled on this key: the word has a double letter here. */
        val doubled: Boolean = false
    )

    suspend fun decode(path: List<FloatArray>, layout: Map<Char, Key>): String? {
        if (path.size < 4 || layout.isEmpty()) return null

        // Step 1: Find key crossings via inflection points + proximity
        val hits = findKeyCrossings(path, layout)
        if (hits.size < 2) {
            // Fallback: simple nearest-key sequence
            val raw = rawSequence(path, layout)
            return raw.ifEmpty { null }?.let { refineWithDictionary(it, path, layout) }
        }

        // Step 2: Build raw letter sequence from crossings. Consecutive hits on one key were
        // already merged, so a double letter is signalled by a loop/wiggle on that key.
        val raw = StringBuilder()
        for (hit in hits) {
            raw.append(hit.char)
            if (hit.doubled) raw.append(hit.char)
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
            val nearest = nearestKey(x, y, keyList)
            if (nearest != null) {
                hits.add(KeyHit(nearest.label[0].lowercaseChar(), x, y, nearest.dist, i))
            }

            // Skip ahead — don't sample every point, just key transitions
            i += if ((nearest?.dist ?: Float.MAX_VALUE) < 20f) 3 else 2
        }

        // Group consecutive hits on the same key into runs; keep the closest hit of each run
        // and flag the run as a double letter when the finger looped on that key.
        val runs = ArrayList<MutableList<KeyHit>>()
        for (hit in hits) {
            if (runs.isEmpty() || runs.last().first().char != hit.char) runs.add(mutableListOf(hit))
            else runs.last().add(hit)
        }
        return runs.map { run ->
            val best = run.minByOrNull { it.dist } ?: run.first()
            val key = keyList.firstOrNull { it.label[0].lowercaseChar() == best.char }
            val looped = key != null && run.size >= 2 &&
                isLoop(path, run.first().pathIndex, run.last().pathIndex, best.char, key, keyList)
            if (looped) best.copy(doubled = true) else best
        }
    }

    private class Nearest(val label: String, val dist: Float)

    private fun nearestKey(x: Float, y: Float, keyList: List<Key>): Nearest? {
        var best: Key? = null
        var bestDist = Float.MAX_VALUE
        for (k in keyList) {
            val d = hypot(x - (k.left + k.right) / 2f, y - (k.top + k.bottom) / 2f)
            if (d < bestDist) { bestDist = d; best = k }
        }
        return best?.let { Nearest(it.label, bestDist) }
    }

    /**
     * True when the stretch of the swipe spent on [key] curls round on itself (a small loop or
     * a back-and-forth wiggle): total turning of at least ~260 degrees. A straight pass has
     * almost no turning and a single U-turn at the end of a word only about 180.
     */
    private fun isLoop(
        path: List<FloatArray>,
        firstHit: Int,
        lastHit: Int,
        char: Char,
        key: Key,
        keyList: List<Key>
    ): Boolean {
        // Widen from the sampled hits to every neighbouring point that is still on this key.
        var a = firstHit
        var b = lastHit
        while (a > 0 && nearestKey(path[a - 1][0], path[a - 1][1], keyList)
                ?.label?.get(0)?.lowercaseChar() == char) a--
        while (b < path.size - 1 && nearestKey(path[b + 1][0], path[b + 1][1], keyList)
                ?.label?.get(0)?.lowercaseChar() == char) b++

        val keyWidth = (key.right - key.left).coerceAtLeast(1f)
        val minStep = keyWidth * 0.08f      // ignore jitter smaller than this
        var anchorX = path[a][0]
        var anchorY = path[a][1]
        var prevDx = 0f
        var prevDy = 0f
        var hasPrev = false
        var arc = 0f
        var turning = 0f
        for (i in a + 1..b) {
            val dx = path[i][0] - anchorX
            val dy = path[i][1] - anchorY
            val len = hypot(dx, dy)
            if (len < minStep) continue
            arc += len
            if (hasPrev) {
                turning += abs(atan2(prevDx * dy - prevDy * dx, prevDx * dx + prevDy * dy))
            }
            prevDx = dx; prevDy = dy; hasPrev = true
            anchorX = path[i][0]; anchorY = path[i][1]
        }
        return arc >= keyWidth * 0.75f && turning >= LOOP_MIN_TURNING
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

        // No loop was seen, but the swipe may still hide a double letter ("helo" -> "hello").
        withOneDoubledLetter(lower).firstOrNull { dictionary.contains(it) }?.let { return it }

        // Get dictionary suggestions for the raw sequence
        val candidates = dictionary.suggestAsync(lower, 12)
        if (candidates.isEmpty()) return lower

        // Also try prefix-based candidates
        if (lower.length >= 3) {
            val prefixCands = dictionary.suggestAsync(lower.substring(0, minOf(3, lower.length)), 8)
            // Merge and deduplicate
            val merged = (candidates + prefixCands).distinctBy { it.lowercase() }
            if (merged.size > 1) {
                return rankBySpatialFit(merged, path, layout).firstOrNull()
            }
        }

        return rankBySpatialFit(candidates, path, layout).firstOrNull() ?: lower
    }

    /**
     * Orders candidates by how well the swipe passes through their letters, best first.
     *
     * The swipe has many more points than a word has letters, so the path is resampled to
     * one point per letter (evenly along its length) before comparing. Comparing letter i
     * with raw point i would only ever look at the start of the swipe.
     */
    internal fun rankBySpatialFit(
        candidates: List<String>,
        path: List<FloatArray>,
        layout: Map<Char, Key>
    ): List<String> {
        if (candidates.size < 2 || path.isEmpty() || layout.isEmpty()) return candidates
        return candidates.sortedBy { spatialCost(it, path, layout) }
    }

    /** Mean squared distance, in key sizes, between each letter's key and its resampled path point. */
    private fun spatialCost(word: String, path: List<FloatArray>, layout: Map<Char, Key>): Float {
        if (word.isEmpty()) return Float.MAX_VALUE
        val points = resample(path, word.length)
        var total = 0f
        var counted = 0
        for (i in word.indices) {
            val key = layout[word[i].lowercaseChar()] ?: continue
            val w = (key.right - key.left).coerceAtLeast(1f)
            val h = (key.bottom - key.top).coerceAtLeast(1f)
            val dx = (points[i][0] - (key.left + key.right) / 2f) / w
            val dy = (points[i][1] - (key.top + key.bottom) / 2f) / h
            total += dx * dx + dy * dy
            counted++
        }
        // Letters without a key (apostrophes etc.) are skipped; a word with none is a poor fit.
        return if (counted == 0) Float.MAX_VALUE else total / counted
    }

    /** [n] points spaced evenly by arc length along [path] (first and last points included). */
    internal fun resample(path: List<FloatArray>, n: Int): List<FloatArray> {
        if (path.isEmpty() || n <= 0) return emptyList()
        if (n == 1 || path.size == 1) return List(n) { path.first() }
        val cumulative = FloatArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] +
                hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
        }
        val total = cumulative.last()
        if (total <= 0f) return List(n) { path.first() }
        val out = ArrayList<FloatArray>(n)
        var seg = 1
        for (j in 0 until n) {
            val target = total * j / (n - 1)
            while (seg < path.size - 1 && cumulative[seg] < target) seg++
            val segLen = cumulative[seg] - cumulative[seg - 1]
            val t = if (segLen <= 0f) 0f else ((target - cumulative[seg - 1]) / segLen).coerceIn(0f, 1f)
            out.add(floatArrayOf(
                path[seg - 1][0] + (path[seg][0] - path[seg - 1][0]) * t,
                path[seg - 1][1] + (path[seg][1] - path[seg - 1][1]) * t
            ))
        }
        return out
    }

    /** [word] with each letter repeated once in turn, left to right, without duplicates. */
    internal fun withOneDoubledLetter(word: String): List<String> {
        if (word.length < 2 || word.length > MAX_DOUBLE_LETTER_WORD) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in word.indices) {
            if (!word[i].isLetter()) continue
            if (i > 0 && word[i] == word[i - 1]) continue   // already doubled here
            out.add(word.substring(0, i + 1) + word.substring(i))
        }
        return out.toList()
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

    private companion object {
        /** Total turning (radians, ~258 degrees) that counts as a loop; a lone U-turn is only ~pi. */
        const val LOOP_MIN_TURNING = 4.5f
        const val MAX_DOUBLE_LETTER_WORD = 16
    }
}
