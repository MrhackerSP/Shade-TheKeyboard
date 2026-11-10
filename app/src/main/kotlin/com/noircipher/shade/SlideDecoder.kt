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
 * 1. Detecting "key crossings" — path points that pass within a fraction of a
 *    key size of a key center (mere "nearest key" doesn't count: a diagonal
 *    swipe would otherwise register every key it passes between)
 * 2. Building a raw letter sequence from those crossings
 * 3. Finding dictionary words that match the raw sequence (exact, doubled
 *    letters, prefix suggestions, and in-order subsequence matches)
 * 4. Scoring candidates by spatial proximity to the actual path
 *
 * The decoder handles:
 * - Double letters (path loops on one key → two of that letter)
 * - Crossed-but-unintended keys (deleted to recover the intended word)
 * - U-turn words ("mom" revisits the first key)
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
     * Find points where the path actually passes over a key center.
     *
     * Only points within [CROSSING_THRESHOLD] of a key center count: on a diagonal
     * swipe every intermediate point still has *some* nearest key, and registering
     * those used to turn the raw sequence into the whole soup of crossed keys
     * (a swipe from A to Y decoded as "asdrty…" and committed every crossed letter).
     */
    private fun findKeyCrossings(path: List<FloatArray>, layout: Map<Char, Key>): List<KeyHit> {
        val hits = ArrayList<KeyHit>()
        val keyList = layout.values.filter { it.label.length == 1 }
        if (keyList.isEmpty()) return hits

        for (i in path.indices) {
            val x = path[i][0]
            val y = path[i][1]

            val nearest = nearestKey(x, y, keyList)
            // Only count the crossing when the finger really passed over the key
            if (nearest != null && nearest.dist <= keyThreshold(nearest.key)) {
                hits.add(KeyHit(nearest.key.label[0].lowercaseChar(), x, y, nearest.dist, i))
            }
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

    private class Nearest(val key: Key, val label: String, val dist: Float)

    private fun nearestKey(x: Float, y: Float, keyList: List<Key>): Nearest? {
        var best: Key? = null
        var bestDist = Float.MAX_VALUE
        for (k in keyList) {
            val d = hypot(x - (k.left + k.right) / 2f, y - (k.top + k.bottom) / 2f)
            if (d < bestDist) { bestDist = d; best = k }
        }
        return best?.let { Nearest(it, it.label, bestDist) }
    }

    /** Max distance from a key center that still counts as "crossed this key". */
    internal fun keyThreshold(k: Key): Float {
        val w = (k.right - k.left).coerceAtLeast(1f)
        val h = (k.bottom - k.top).coerceAtLeast(1f)
        return CROSSING_THRESHOLD * minOf(w, h)
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
        val candidates = LinkedHashSet<String>()
        candidates.addAll(dictionary.suggestAsync(lower, 12))

        // Also try prefix-based candidates
        if (lower.length >= 3) {
            candidates.addAll(dictionary.suggestAsync(lower.substring(0, minOf(3, lower.length)), 8))
        }

        // Prefix matching fails when the finger crossed unrelated keys on the way:
        // also offer words whose letters appear in order along the crossed keys
        // ("hjui" traced on the way to "hi", "dfguioihg" on the way to "dog").
        candidates.addAll(subsequenceWords(lower))

        if (candidates.isEmpty()) return lower

        val merged = candidates.distinctBy { it.lowercase() }
        if (merged.size > 1) {
            // Spatial fit, plus a small penalty per skipped crossed key: when several
            // words fit the path equally well ("hell" vs "hello" traced through all
            // five keys), prefer the one that explains more of the swipe.
            val rawLen = lower.length.coerceAtLeast(1)
            return merged.sortedBy { rankingCost(it, path, layout, rawLen) }.firstOrNull()
                ?: merged.first()
        }

        return merged.firstOrNull() ?: lower
    }

    /** Spatial cost adjusted by how many crossed keys the word leaves unexplained. */
    private fun rankingCost(word: String, path: List<FloatArray>, layout: Map<Char, Key>, rawLength: Int): Float {
        val skipped = (rawLength - word.length).coerceAtLeast(0)
        return spatialCost(word, path, layout) + SKIP_PENALTY * skipped / rawLength
    }

    /**
     * Dictionary words obtainable by deleting letters from [raw] without reordering.
     * Tries longer matches first; bounded to [MAX_SUBSEQ_RAW] letters so the 2^n
     * enumeration stays trivial (< 16k lookups) on a phone.
     */
    internal fun subsequenceWords(raw: String): List<String> {
        val n = raw.length
        if (n < 2 || n > MAX_SUBSEQ_RAW) return emptyList()
        val letters = raw.lowercase().toCharArray()
        val out = LinkedHashSet<String>()
        // Bitmask over letter slots; require at least 2 letters per candidate.
        val masks = 1 shl n
        for (mask in 0 until masks) {
            if (mask.countOneBits() < 2) continue
            val sb = StringBuilder(mask.countOneBits())
            for (i in 0 until n) {
                if (mask and (1 shl i) != 0) sb.append(letters[i])
            }
            val w = sb.toString()
            if (dictionary.contains(w)) {
                out.add(w)
                if (out.size >= MAX_SUBSEQ_CANDIDATES) break
            }
        }
        return out.toList()
    }

    /**
     * Orders candidates by how well the swipe passes through their letters, best first.
     *
     * Each letter is matched against its closest path point with a monotonic pointer
     * (see [spatialCost]), so candidates are compared on where the finger actually
     * went, not on letter count or raw point order.
     */
    internal fun rankBySpatialFit(
        candidates: List<String>,
        path: List<FloatArray>,
        layout: Map<Char, Key>
    ): List<String> {
        if (candidates.size < 2 || path.isEmpty() || layout.isEmpty()) return candidates
        return candidates.sortedBy { spatialCost(it, path, layout) }
    }

    /**
     * Mean squared distance, in key sizes, between each letter's key and the path.
     *
     * Each letter is matched against its closest path point with a monotonic pointer,
     * so a loop drawn for a double letter doesn't distort the measurement the way
     * arc-length resampling does, and a letter revisited later in the swipe (a
     * U-turn word like "mom") can match its later visit.
     */
    private fun spatialCost(word: String, path: List<FloatArray>, layout: Map<Char, Key>): Float {
        if (word.isEmpty() || path.isEmpty()) return Float.MAX_VALUE
        var pointer = 0
        var total = 0f
        var counted = 0
        for (ch in word) {
            val key = layout[ch.lowercaseChar()] ?: continue
            val cx = (key.left + key.right) / 2f
            val cy = (key.top + key.bottom) / 2f
            val w = (key.right - key.left).coerceAtLeast(1f)
            val h = (key.bottom - key.top).coerceAtLeast(1f)
            var bestJ = pointer
            var bestCost = Float.MAX_VALUE
            for (j in pointer until path.size) {
                val dx = (path[j][0] - cx) / w
                val dy = (path[j][1] - cy) / h
                val cost = dx * dx + dy * dy
                if (cost < bestCost) { bestCost = cost; bestJ = j }
            }
            pointer = bestJ
            total += bestCost
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
            var best: Key? = null
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
                    best = key
                }
            }
            val key = best
            // Same distance gate as findKeyCrossings: a point whose nearest key is
            // still a full key-width away is travel, not a crossed letter.
            if (key != null && bestDist <= keyThreshold(key) * keyThreshold(key)) {
                val c = key.label[0].lowercaseChar()
                if (c != last) {
                    out.append(c)
                    last = c
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** Total turning (radians, ~258 degrees) that counts as a loop; a lone U-turn is only ~pi. */
        const val LOOP_MIN_TURNING = 4.5f
        const val MAX_DOUBLE_LETTER_WORD = 16
        /** Fraction of key size within which a path point counts as crossing that key. */
        const val CROSSING_THRESHOLD = 0.4f
        /** Weight of the skipped-crossed-keys tie-break in [rankingCost]. */
        const val SKIP_PENALTY = 0.3f
        /** Raw sequences longer than this skip the subsequence-word search. */
        const val MAX_SUBSEQ_RAW = 14
        const val MAX_SUBSEQ_CANDIDATES = 64
    }
}
