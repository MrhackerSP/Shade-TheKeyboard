package com.noircipher.shade.slide

import com.noircipher.shade.Key
import com.noircipher.shade.WordDictionary
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gesture-based glide typing decoder.
 *
 * Takes a swipe path (list of x,y points) and decodes it into a word by:
 * 1. Resampling the path to evenly-spaced points
 * 2. For each dictionary word, aligning its letters to the path (DP)
 * 3. Scoring by: letter-path alignment + shape match + corner match + length ratio + word frequency
 * 4. Returning the best-scoring words
 *
 * The decoder handles:
 * - Double letters (path stays near one key)
 * - Skipped letters (path passes near a key but doesn't stop)
 * - Inflection points (direction changes → letter boundaries)
 * - Shape matching (overall path shape vs ideal letter-to-letter path)
 * - End-point matching (first/last letters must be near path start/end)
 */

/** The slice of the dictionary the decoder needs (lets tests use a tiny fake). */
interface SlideDictionary {
    fun contains(word: String): Boolean
    suspend fun suggestAsync(prefix: String, max: Int): List<String>
    fun glideLexicon(): List<Pair<String, Float>>
}

class SlideDecoder(
    private val dictionary: SlideDictionary
) {
    constructor(dictionary: WordDictionary) : this(object : SlideDictionary {
        override fun contains(word: String) = dictionary.contains(word)
        override suspend fun suggestAsync(prefix: String, max: Int) = dictionary.suggestAsync(prefix, max)
        override fun glideLexicon() = dictionary.glideLexicon()
    })

    private data class KeyHit(
        val char: Char, val x: Float, val y: Float, val dist: Float, val pathIndex: Int,
        val doubled: Boolean = false
    )

    // ── Tunable parameters ──────────────────────────────────────────────────
    private object P {
        /** Scatter of finger pass near a letter, in key widths. */
        var sigma = 0.7f
        /** Scatter at start/end of the swipe (first/last letters). */
        var endSigma = 0.54f
        /** Language model weight (word frequency prior). */
        var lm = 0.45f
        /** Shape match weight. */
        var shape = 3.0f
        /** Corner match weight. */
        var corner = 2.9f
        /** Length ratio weight. */
        var length = 1.6f
        /** Max distance (in key widths) from start/end of path to first/last letter. */
        var endRadius = 1.35f
    }

    private companion object {
        const val SHAPE_N = 24
    }

    suspend fun decode(path: List<FloatArray>, layout: Map<Char, Key>): String? {
        if (path.size < 4 || layout.isEmpty()) return null

        // Step 1: Find key crossings via inflection points + proximity
        val hits = findKeyCrossings(path, layout)
        if (hits.size < 2) {
            val raw = rawSequence(path, layout)
            return raw.ifEmpty { null }?.let { refineWithDictionary(it, path, layout) }
        }

        // Step 2: Build raw letter sequence from crossings
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
     */
    private fun findKeyCrossings(path: List<FloatArray>, layout: Map<Char, Key>): List<KeyHit> {
        val hits = ArrayList<KeyHit>()
        val keyList = layout.values.filter { it.label.length == 1 }
        if (keyList.isEmpty()) return hits

        for (i in path.indices) {
            val x = path[i][0]
            val y = path[i][1]
            val nearest = nearestKey(x, y, keyList)
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
            val d = sqrt((x - (k.left + k.right) / 2f) * (x - (k.left + k.right) / 2f) +
                (y - (k.top + k.bottom) / 2f) * (y - (k.top + k.bottom) / 2f))
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
     * a back-and-forth wiggle): total turning of at least ~260 degrees.
     */
    private fun isLoop(
        path: List<FloatArray>,
        firstHit: Int,
        lastHit: Int,
        char: Char,
        key: Key,
        keyList: List<Key>
    ): Boolean {
        var a = firstHit
        var b = lastHit
        while (a > 0 && nearestKey(path[a - 1][0], path[a - 1][1], keyList)
                ?.label?.get(0)?.lowercaseChar() == char) a--
        while (b < path.size - 1 && nearestKey(path[b + 1][0], path[b + 1][1], keyList)
                ?.label?.get(0)?.lowercaseChar() == char) b++

        val keyWidth = (key.right - key.left).coerceAtLeast(1f)
        val minStep = keyWidth * 0.08f
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
            val len = sqrt(dx * dx + dy * dy)
            if (len < minStep) continue
            arc += len
            if (hasPrev) {
                turning += abs(atan2(prevDx * dy - prevDy * dx, prevDx * dx + prevDy * dx))
            }
            prevDx = dx; prevDy = dy; hasPrev = true
            anchorX = path[i][0]; anchorY = path[i][1]
        }
        return arc >= keyWidth * 0.75f && turning >= LOOP_MIN_TURNING
    }

    /**
     * Refine the raw sequence using the dictionary: find the best-matching word.
     * Uses DP alignment + shape matching + corner detection + length ratio + word frequency.
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

        // Subsequence fallback: words whose letters appear in order along the crossed keys
        candidates.addAll(subsequenceWords(lower))

        if (candidates.isEmpty()) return lower

        val merged = candidates.distinctBy { it.lowercase() }

        // Use the full scoring algorithm
        return scoreCandidates(merged, path, layout, lower)
    }

    /**
     * Full scoring: DP alignment + shape match + corner match + length ratio + word frequency.
     */
    private fun scoreCandidates(
        candidates: List<String>,
        path: List<FloatArray>,
        layout: Map<Char, Key>,
        raw: String
    ): String? {
        if (candidates.isEmpty()) return raw

        // Compute key positions in normalized coordinates (key widths)
        val (px, py) = pitch(layout)
        val norm = layout.mapValues { (_, k) ->
            floatArrayOf((k.left + k.right) / 2f / px, (k.top + k.bottom) / 2f / py)
        }
        val rawNorm = path.map { floatArrayOf(it[0] / px, it[1] / py) }

        // Resample the path
        var pathLen = 0f
        for (i in 1 until rawNorm.size) {
            pathLen += dist(rawNorm[i], rawNorm[i - 1])
        }
        val m = (pathLen / 0.22f).toInt().coerceIn(16, 96)
        val pts = resample(rawNorm, m)
        val start = pts.first()
        val end = pts.last()

        // Shape: resample both the actual path and ideal paths to SHAPE_N points
        val shapePts = resample(rawNorm, SHAPE_N)

        // Corners: sharp turns in the path
        val corners = corners(pts)

        val sig2 = 2f * P.sigma * P.sigma
        val end2 = 2f * P.endSigma * P.endSigma
        val r2 = P.endRadius * P.endRadius

        val lexicon = dictionary.glideLexicon()
        val freqMap = lexicon.toMap()

        var bestWord: String? = null
        var bestScore = Float.MAX_VALUE

        val ideal = ArrayList<FloatArray>(24)
        val dp = FloatArray(m)
        val pref = FloatArray(m)

        for (word in candidates) {
            if (word.length < 2) continue
            val f0 = norm[word[0]] ?: continue
            if (dist2(f0, start) > r2) continue
            val lc = word[word.length - 1]
            val l = norm[lc] ?: continue
            if (dist2(l, end) > r2) continue

            // Build ideal path through the word's letters
            ideal.clear()
            var ok = true
            for (ch in word) {
                if (ch == '\'' || ch == '-') continue
                val k = norm[ch] ?: run { ok = false; break }
                if (ideal.isEmpty() || ideal.last() != k) ideal.add(k)
            }
            if (!ok || ideal.size < 2) continue

            // Length ratio check
            var idealLen = 0f
            for (i in 1 until ideal.size) idealLen += dist(ideal[i], ideal[i - 1])
            val ratio = ln((pathLen + 0.3f) / (idealLen + 0.3f))
            if (abs(ratio) > 1.1f) continue

            // DP alignment: match each letter to the closest point on the path, monotonically
            val n = ideal.size
            val first = ideal[0]
            for (j in 0 until m) dp[j] = if (j == 0) dist2(first, pts[0]) / end2 else Float.MAX_VALUE
            for (i in 1 until n) {
                var run = Float.MAX_VALUE
                for (j in 0 until m) {
                    if (dp[j] < run) run = dp[j]
                    pref[j] = run
                }
                val k = ideal[i]
                if (i == n - 1) {
                    for (j in 0 until m - 1) dp[j] = Float.MAX_VALUE
                    dp[m - 1] = pref[m - 1] + dist2(k, pts[m - 1]) / end2
                } else {
                    for (j in 0 until m) dp[j] = if (pref[j] == Float.MAX_VALUE) Float.MAX_VALUE else pref[j] + dist2(k, pts[j]) / sig2
                }
            }
            val align = dp[m - 1]
            if (align > 40f) continue

            // Corner penalty: sharp turns should be near word letters
            var turn = 0f
            for (c in corners) {
                var best = Float.MAX_VALUE
                for (k in ideal) best = min(best, dist2(k, pts[c]))
                val d = sqrt(best)
                if (d > 0.6f) turn += d - 0.6f
            }

            // Shape: compare resampled actual path to resampled ideal path
            val ip = resample(ideal, SHAPE_N)
            var shapeSum = 0f
            for (i in 0 until SHAPE_N) shapeSum += dist(shapePts[i], ip[i])
            val shapeScore = shapeSum / SHAPE_N

            // Word frequency prior (more common = lower ln(freq) = lower score)
            val prior = freqMap[word] ?: 10f

            val score = align + P.shape * shapeScore + P.corner * turn + P.length * ratio * ratio + P.lm * prior

            if (score < bestScore) {
                bestScore = score
                bestWord = word
            }
        }

        return bestWord ?: raw
    }

    // ── Geometry helpers ────────────────────────────────────────────────────

    /** Key pitch: horizontal step (key width) and vertical step (row height) from key centers. */
    private fun pitch(layout: Map<Char, Key>): Pair<Float, Float> {
        val pts = layout.values.map { floatArrayOf((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
        val keyW = layout.values.firstOrNull()?.let { it.right - it.left } ?: 100f
        val dx = ArrayList<Float>()
        val rows = ArrayList<Float>()
        for ((pi, p) in pts.withIndex()) {
            var best = Float.MAX_VALUE
            for ((qi, q) in pts.withIndex()) {
                if (qi == pi) continue
                if (abs(q[1] - p[1]) < keyW * 0.3f) {
                    val d = abs(q[0] - p[0])
                    if (d > keyW * 0.3f && d < best) best = d
                }
            }
            if (best < Float.MAX_VALUE) dx.add(best)
            if (rows.none { abs(it - p[1]) < keyW * 0.3f }) rows.add(p[1])
        }
        dx.sort()
        rows.sort()
        val px = if (dx.isEmpty()) keyW else dx[dx.size / 2]
        val gaps = (1 until rows.size).map { rows[it] - rows[it - 1] }.sorted()
        val py = if (gaps.isEmpty()) px * 1.5f else gaps[gaps.size / 2]
        return px to py
    }

    private fun d2(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        return x * x + y * y
    }

    private fun dist(a: FloatArray, b: FloatArray): Float = sqrt(d2(a, b))
    private fun dist2(a: FloatArray, b: FloatArray): Float = d2(a, b)

    /** Resample [src] to exactly [n] points, evenly spaced by arc length. */
    internal fun resample(src: List<FloatArray>, n: Int): Array<FloatArray> {
        if (n <= 0) return emptyArray()
        if (src.isEmpty()) return emptyArray()
        if (src.size == 1) return Array(n) { src[0] }
        var total = 0f
        for (i in 1 until src.size) total += dist(src[i], src[i - 1])
        if (total <= 0f) return Array(n) { src[0] }
        val step = total / (n - 1)
        val out = ArrayList<FloatArray>(n)
        out.add(src[0])
        var acc = 0f
        var prev = src[0]
        var i = 1
        while (i < src.size && out.size < n) {
            val cur = src[i]
            val d = dist(cur, prev)
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                val q = floatArrayOf(prev[0] + (cur[0] - prev[0]) * t, prev[1] + (cur[1] - prev[1]) * t)
                out.add(q)
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out.add(src.last())
        return out.toTypedArray()
    }

    /** Find sharp turns (>60°) in the path — where the finger changes direction at a letter. */
    private fun corners(pts: Array<FloatArray>): List<Int> {
        val w = 2
        val out = ArrayList<Int>()
        var lastAngle = 0f
        for (i in w until pts.size - w) {
            val ax = pts[i][0] - pts[i - w][0]
            val ay = pts[i][1] - pts[i - w][1]
            val bx = pts[i + w][0] - pts[i][0]
            val by = pts[i + w][1] - pts[i][1]
            val la = sqrt(ax * ax + ay * ay)
            val lb = sqrt(bx * bx + by * by)
            if (la < 0.12f || lb < 0.12f) continue
            val cos = (ax * bx + ay * by) / (la * lb)
            if (cos > 0.5f) continue
            val angle = 1f - cos
            if (out.isNotEmpty() && i - out.last() <= w) {
                if (angle > lastAngle) {
                    out[out.lastIndex] = i
                    lastAngle = angle
                }
                continue
            }
            out.add(i)
            lastAngle = angle
        }
        return out
    }

    /** [word] with each letter repeated once in turn, left to right, without duplicates. */
    internal fun withOneDoubledLetter(word: String): List<String> {
        if (word.length < 2 || word.length > MAX_DOUBLE_LETTER_WORD) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in word.indices) {
            if (!word[i].isLetter()) continue
            if (i > 0 && word[i] == word[i - 1]) continue
            out.add(word.substring(0, i + 1) + word.substring(i))
        }
        return out.toList()
    }

    /** Dictionary words obtainable by deleting letters from [raw] without reordering. */
    internal fun subsequenceWords(raw: String): List<String> {
        val n = raw.length
        if (n < 2 || n > MAX_SUBSEQ_RAW) return emptyList()
        val letters = raw.lowercase().toCharArray()
        val out = LinkedHashSet<String>()
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
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d
                    best = key
                }
            }
            val key = best
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

    private fun atan2(y: Float, x: Float): Float = kotlin.math.atan2(y, x)

    private companion object {
        const val LOOP_MIN_TURNING = 4.5f
        const val MAX_DOUBLE_LETTER_WORD = 16
        const val CROSSING_THRESHOLD = 0.4f
        const val MAX_SUBSEQ_RAW = 14
        const val MAX_SUBSEQ_CANDIDATES = 64
    }
}
