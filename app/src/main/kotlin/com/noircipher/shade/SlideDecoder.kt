package com.noircipher.shade.slide

import com.noircipher.shade.Key
import com.noircipher.shade.WordDictionary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Glide ("swipe") typing decoder.
 *
 * Every word of the lexicon is a candidate. Instead of first guessing which letters the finger
 * crossed and then repairing that guess (which loses the intended word as soon as one letter is
 * clipped at a corner), each word is *drawn* as the polyline through its letters and compared
 * with the swipe:
 *
 *  1. Pruning: the first letter must lie near where the swipe starts and the last letter near where
 *     it ends, and the word's drawn length must be in the same range as the swipe's.
 *  2. Stage 1 (cheap, every surviving word): the letters are aligned to the swipe in order (DP), the
 *     length ratio is checked and the word-frequency prior is added. The best [STAGE1_KEEP] go on.
 *     The caller's context bonus (how well the word fits after the previous words, from the neural
 *     language model) is part of this stage, so a likely word is not cut for being uncommon.
 *  3. Stage 2: the shape of the drawn word is compared with the swipe (uniform resampling), every
 *     swipe point is checked against the drawn polyline (a swipe that wanders over other keys does
 *     not match), direction changes of the swipe must sit on letters, and a loop drawn on a key
 *     favours the doubled spelling.
 *
 * The best-scoring word is returned even for sloppy swipes, but never a string of crossed letters
 * that is not a word. Tuned against simulated finger paths over the full 185k-word dictionary.
 */

/** The slice of the dictionary the decoder needs (lets tests use a tiny fake). */
interface SlideDictionary {
    fun contains(word: String): Boolean

    /** Bundled words. Expensive to build, so implementations must cache it. */
    fun glideLexicon(): GlideLexicon

    /** Words the person taught the keyboard; small, may change between swipes. */
    fun glideUserLexicon(): GlideLexicon = GlideLexicon.EMPTY
}

class SlideDecoder(
    private val dictionary: SlideDictionary
) {
    constructor(dictionary: WordDictionary) : this(object : SlideDictionary {
        override fun contains(word: String) = dictionary.contains(word)
        override fun glideLexicon() = dictionary.glideLexicon()
        override fun glideUserLexicon() = dictionary.glideUserLexicon()
    })

    /**
     * Best word for the swipe, or null when nothing plausible fits.
     *
     * @param path    touch points of the swipe in view coordinates.
     * @param layout  letter -> key, used for the key centres.
     * @param context optional fit (0..1) of each likely next word given the previous words; it is
     *                weighted by [CONTEXT_WEIGHT], so 1 outweighs a large gap in word frequency.
     */
    suspend fun decode(
        path: List<FloatArray>,
        layout: Map<Char, Key>,
        context: Map<String, Float> = emptyMap()
    ): String? = decodeCandidates(path, layout, 1, context).firstOrNull()

    /** The [max] best words, best first. */
    suspend fun decodeCandidates(
        path: List<FloatArray>,
        layout: Map<Char, Key>,
        max: Int = 3,
        context: Map<String, Float> = emptyMap()
    ): List<String> {
        if (path.isEmpty() || max <= 0) return emptyList()
        val geo = buildGeometry(layout) ?: return emptyList()

        // Normalise into key units and drop repeated points.
        val xs = FloatArray(path.size)
        val ys = FloatArray(path.size)
        var n = 0
        for (p in path) {
            if (p.size < 2) continue
            val x = p[0] / geo.px
            val y = p[1] / geo.py
            if (x.isNaN() || y.isNaN()) continue
            if (n > 0) {
                val dx = x - xs[n - 1]
                val dy = y - ys[n - 1]
                if (dx * dx + dy * dy < 1e-6f) continue
            }
            xs[n] = x
            ys[n] = y
            n++
        }
        if (n == 0) return emptyList()
        var length = 0f
        for (i in 1 until n) length += dist(xs[i], ys[i], xs[i - 1], ys[i - 1])
        if (n < 2 || length < TINY_PATH) return tinyPath(xs, ys, n, geo)

        val run = Run(geo, xs, ys, n, length, context)
        var found = run.scan(dictionary.glideLexicon(), dictionary.glideUserLexicon(), relaxed = false)
        if (found.isEmpty()) found = run.scan(dictionary.glideLexicon(), dictionary.glideUserLexicon(), relaxed = true)
        return found.take(max)
    }

    // ---------------------------------------------------------------- layout geometry

    /** Letter centres in key units (1 unit = one key pitch horizontally / one row pitch vertically). */
    private class Geometry(
        val px: Float,
        val py: Float,
        val x: FloatArray,
        val y: FloatArray,
        val present: BooleanArray,
        val complete: Boolean
    ) {
        /** Distance in key units between every pair of letters (NaN if either is missing). */
        val pair = FloatArray(26 * 26).also { t ->
            for (a in 0 until 26) for (b in 0 until 26) {
                t[a * 26 + b] =
                    if (present[a] && present[b]) dist(x[a], y[a], x[b], y[b]) else Float.NaN
            }
        }
    }

    private fun buildGeometry(layout: Map<Char, Key>): Geometry? {
        val keys = arrayOfNulls<Key>(26)
        var count = 0
        for ((ch, key) in layout) {
            val c = GlideLexicon.codeOf(ch)
            if (c < 0 || keys[c] != null) continue
            keys[c] = key
            count++
        }
        if (count < 2) return null
        val list = keys.filterNotNull()
        val widths = list.map { it.right - it.left }.filter { it > 0f }.sorted()
        if (widths.isEmpty()) return null
        val heights = list.map { it.bottom - it.top }.filter { it > 0f }.sorted()
        val kw = widths[widths.size / 2]
        val kh = if (heights.isEmpty()) kw else heights[heights.size / 2]

        // Horizontal pitch: distance to the nearest neighbour in the same row (median over keys).
        val hp = ArrayList<Float>()
        for (a in list) {
            var best = Float.MAX_VALUE
            for (b in list) {
                if (a === b) continue
                if (abs(a.centerY - b.centerY) > kh * 0.3f) continue
                val d = abs(a.centerX - b.centerX)
                if (d > kw * 0.3f && d < best) best = d
            }
            if (best < Float.MAX_VALUE) hp.add(best)
        }
        val px = if (hp.isEmpty()) kw else hp.sorted()[hp.size / 2]

        // Vertical pitch: gap between distinct row centres (median).
        val rows = ArrayList<Float>()
        for (y in list.map { it.centerY }.sorted()) {
            if (rows.isEmpty() || y - rows.last() > kh * 0.5f) rows.add(y)
        }
        val vp = ArrayList<Float>()
        for (i in 1 until rows.size) vp.add(rows[i] - rows[i - 1])
        val py = if (vp.isEmpty()) kh else vp.sorted()[vp.size / 2]
        if (px <= 0f || py <= 0f) return null

        val x = FloatArray(26)
        val y = FloatArray(26)
        val present = BooleanArray(26)
        for (c in 0 until 26) {
            val k = keys[c] ?: continue
            x[c] = k.centerX / px
            y[c] = k.centerY / py
            present[c] = true
        }
        return Geometry(px, py, x, y, present, complete = count == 26)
    }

    /** A swipe that never really left one key: only a one-letter word ("a", "I") makes sense. */
    private fun tinyPath(xs: FloatArray, ys: FloatArray, n: Int, geo: Geometry): List<String> {
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) { cx += xs[i]; cy += ys[i] }
        cx /= n
        cy /= n
        var best = -1
        var bestD = Float.MAX_VALUE
        for (c in 0 until 26) {
            if (!geo.present[c]) continue
            val d = dist(cx, cy, geo.x[c], geo.y[c])
            if (d < bestD) { bestD = d; best = c }
        }
        if (best < 0 || bestD > 0.6f) return emptyList()
        val letter = ('a' + best).toString()
        return if (dictionary.contains(letter)) listOf(letter) else emptyList()
    }

    // ---------------------------------------------------------------- one decode

    private class Hit(val word: String, val score: Float)

    private class Run(
        val geo: Geometry,
        xs: FloatArray,
        ys: FloatArray,
        n: Int,
        val length: Float,
        val context: Map<String, Float>
    ) {
        val hasContext = context.isNotEmpty()
        val m: Int = min(M_MAX, max(M_MIN, round(length / RES).toInt()))
        val px = FloatArray(m)
        val py = FloatArray(m)
        val shapeX = FloatArray(SHAPE_N)
        val shapeY = FloatArray(SHAPE_N)
        val corners: IntArray
        val loopMask: Int

        // Reused per word.
        private val dp = FloatArray(m)
        private val vx = FloatArray(GlideLexicon.MAX_LETTERS)
        private val vy = FloatArray(GlideLexicon.MAX_LETTERS)
        private val cum = FloatArray(GlideLexicon.MAX_LETTERS)

        init {
            resample(xs, ys, n, px, py, m)
            resample(xs, ys, n, shapeX, shapeY, SHAPE_N)
            corners = findCorners(px, py, m)
            loopMask = findLoops(xs, ys, n, geo)
        }

        // Stage-1 survivors, kept as parallel arrays (a bounded "worst out" selection).
        private val keepScore = FloatArray(STAGE1_KEEP)
        private val keepLex = IntArray(STAGE1_KEEP)
        private val keepIdx = IntArray(STAGE1_KEEP)
        private var kept = 0
        private var worstPos = 0
        private var worstScore = Float.NEGATIVE_INFINITY

        private fun offer(score: Float, lex: Int, idx: Int) {
            if (kept < STAGE1_KEEP) {
                keepScore[kept] = score; keepLex[kept] = lex; keepIdx[kept] = idx
                kept++
                if (kept == STAGE1_KEEP) findWorst()
            } else if (score < worstScore) {
                keepScore[worstPos] = score; keepLex[worstPos] = lex; keepIdx[worstPos] = idx
                findWorst()
            }
        }

        private fun findWorst() {
            var w = 0
            for (i in 1 until kept) if (keepScore[i] > keepScore[w]) w = i
            worstPos = w
            worstScore = keepScore[w]
        }

        suspend fun scan(bundled: GlideLexicon, user: GlideLexicon, relaxed: Boolean): List<String> {
            kept = 0
            worstScore = Float.NEGATIVE_INFINITY
            val sr = if (relaxed) RELAXED_START_R else START_R
            val er = if (relaxed) RELAXED_END_R else END_R
            val rmax = if (relaxed) RELAXED_RMAX else RMAX
            val startOk = BooleanArray(26)
            val endOk = BooleanArray(26)
            for (c in 0 until 26) {
                if (!geo.present[c]) continue
                startOk[c] = dist(geo.x[c], geo.y[c], px[0], py[0]) <= sr
                endOk[c] = dist(geo.x[c], geo.y[c], px[m - 1], py[m - 1]) <= er
            }
            stage1(bundled, 0, startOk, endOk, rmax)
            stage1(user, 1, startOk, endOk, rmax)
            if (kept == 0) return emptyList()
            return stage2(bundled, user)
        }

        private suspend fun stage1(
            lex: GlideLexicon,
            lexId: Int,
            startOk: BooleanArray,
            endOk: BooleanArray,
            rmax: Float
        ) {
            val s2 = 2f * SIGMA * SIGMA
            val e2 = 2f * END_SIGMA * END_SIGMA
            val endX = px[m - 1]
            val endY = py[m - 1]
            for (i in 0 until lex.size) {
                if ((i and 511) == 0) currentCoroutineContext().ensureActive()
                val c = lex.codes[i]
                val len = c.size
                if (!startOk[c[0].toInt()] || !endOk[c[len - 1].toInt()]) continue
                if (!geo.complete && !allPresent(c)) continue

                var il = 0f
                for (j in 1 until len) il += geo.pair[c[j - 1] * 26 + c[j]]
                val ratio = ln((length + 0.4f) / (il + 0.4f))
                if (abs(ratio) > rmax) continue

                // Letters aligned to the swipe in order; first/last anchored to its ends.
                java.util.Arrays.fill(dp, Float.POSITIVE_INFINITY)
                val f = c[0].toInt()
                dp[0] = sq(geo.x[f] - px[0], geo.y[f] - py[0]) / e2
                for (k in 1 until len - 1) {
                    val lx = geo.x[c[k].toInt()]
                    val ly = geo.y[c[k].toInt()]
                    var runMin = Float.POSITIVE_INFINITY
                    for (j in 0 until m) {
                        val old = dp[j]
                        if (old < runMin) runMin = old
                        dp[j] = if (runMin == Float.POSITIVE_INFINITY) runMin
                        else runMin + sq(lx - px[j], ly - py[j]) / s2
                    }
                }
                var best = Float.POSITIVE_INFINITY
                for (j in 0 until m) if (dp[j] < best) best = dp[j]
                val l = c[len - 1].toInt()
                val align = best + sq(geo.x[l] - endX, geo.y[l] - endY) / e2

                var s1 = align + W_LEN * ratio * ratio + W_LM * (1f - lex.prior[i])
                if (hasContext) {
                    val fit = context[lex.words[i]]
                    if (fit != null) s1 -= CONTEXT_WEIGHT * fit
                }
                offer(s1, lexId, i)
            }
        }

        private fun allPresent(c: ByteArray): Boolean {
            for (b in c) if (!geo.present[b.toInt()]) return false
            return true
        }

        private fun stage2(bundled: GlideLexicon, user: GlideLexicon): List<String> {
            val hits = ArrayList<Hit>(kept)
            for (h in 0 until kept) {
                val lex = if (keepLex[h] == 0) bundled else user
                val i = keepIdx[h]
                val c = lex.codes[i]
                val len = c.size
                for (k in 0 until len) {
                    vx[k] = geo.x[c[k].toInt()]
                    vy[k] = geo.y[c[k].toInt()]
                }
                cum[0] = 0f
                for (k in 1 until len) cum[k] = cum[k - 1] + dist(vx[k], vy[k], vx[k - 1], vy[k - 1])

                // Shape: the drawn word resampled uniformly vs the swipe resampled the same way.
                val total = cum[len - 1]
                var shape = 0f
                var seg = 0
                for (q in 0 until SHAPE_N) {
                    val t = total * q / (SHAPE_N - 1)
                    while (seg < len - 2 && cum[seg + 1] <= t) seg++
                    val span = cum[seg + 1] - cum[seg]
                    val u = if (span > 1e-9f) (t - cum[seg]) / span else 0f
                    val qx = vx[seg] + (vx[seg + 1] - vx[seg]) * u
                    val qy = vy[seg] + (vy[seg + 1] - vy[seg]) * u
                    shape += dist(qx, qy, shapeX[q], shapeY[q])
                }
                shape /= SHAPE_N

                // Adherence: how far every swipe point strays from the drawn polyline.
                var adh = 0f
                for (j in 0 until m) {
                    var d = Float.MAX_VALUE
                    for (k in 0 until len - 1) {
                        val dd = pointSegment(px[j], py[j], vx[k], vy[k], vx[k + 1], vy[k + 1])
                        if (dd < d) d = dd
                    }
                    val over = d - ADH_TOL
                    if (over > 0f) adh += over * over
                }
                adh /= m

                // Corners: where the swipe turns sharply there should be a letter.
                var turn = 0f
                for (cIdx in corners) {
                    var d = Float.MAX_VALUE
                    for (k in 0 until len) {
                        val dd = dist(vx[k], vy[k], px[cIdx], py[cIdx])
                        if (dd < d) d = dd
                    }
                    if (d > CORNER_FREE) turn += d - CORNER_FREE
                }

                val s1 = keepScore[h]
                var score = s1 + W_SHAPE * shape + W_ADH * adh + W_CORNER * turn
                if ((lex.doubleMask[i] and loopMask) != 0) score -= LOOP_BONUS
                hits.add(Hit(lex.words[i], score))
            }
            hits.sortBy { it.score }
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            for (h in hits) if (seen.add(h.word)) out.add(h.word)
            return out
        }
    }

    private companion object {
        // Tuned on simulated swipes over the full dictionary (see SlideDecoderTest for the behaviours).
        const val SIGMA = 0.9f
        const val END_SIGMA = 0.5f
        const val W_SHAPE = 3.0f
        const val W_ADH = 5.0f
        const val W_CORNER = 1.0f
        const val W_LEN = 1.6f
        const val W_LM = 6.0f
        const val ADH_TOL = 0.4f
        const val CORNER_FREE = 0.6f
        const val START_R = 1.1f
        const val END_R = 1.1f
        const val RMAX = 1.0f
        const val RELAXED_START_R = 1.9f
        const val RELAXED_END_R = 1.9f
        const val RELAXED_RMAX = 2.0f
        const val LOOP_BONUS = 2.0f
        // Measured on natural sentences with the real neural model: with the right context this
        // takes first-choice accuracy from 83% to 94% at normal swipe noise, and with a completely
        // unrelated context it stays at 84%. Higher weights gain little and start to hurt there.
        const val CONTEXT_WEIGHT = 6.0f
        const val STAGE1_KEEP = 250
        const val SHAPE_N = 32
        const val RES = 0.16f
        const val M_MIN = 24
        const val M_MAX = 72
        const val TINY_PATH = 0.45f
        const val LOOP_MIN_TURNING = 4.5f
        const val LOOP_MIN_ARC = 0.75f
        const val LOOP_RADIUS = 0.5f

        fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }

        fun sq(dx: Float, dy: Float) = dx * dx + dy * dy

        fun pointSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val abx = bx - ax
            val aby = by - ay
            val ab2 = abx * abx + aby * aby
            val t = if (ab2 > 1e-9f) (((x - ax) * abx + (y - ay) * aby) / ab2).coerceIn(0f, 1f) else 0f
            return dist(x, y, ax + abx * t, ay + aby * t)
        }

        /** Resamples the polyline (xs, ys)[0 until n] into [m] points evenly spaced along its length. */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int, ox: FloatArray, oy: FloatArray, m: Int) {
            val cum = FloatArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + dist(xs[i], ys[i], xs[i - 1], ys[i - 1])
            val total = cum[n - 1]
            if (n < 2 || total <= 0f) {
                for (k in 0 until m) { ox[k] = xs[0]; oy[k] = ys[0] }
                return
            }
            var seg = 1
            for (k in 0 until m) {
                val target = total * k / (m - 1)
                while (seg < n - 1 && cum[seg] < target) seg++
                val a = cum[seg - 1]
                val b = cum[seg]
                val t = if (b > a) ((target - a) / (b - a)).coerceIn(0f, 1f) else 0f
                ox[k] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
                oy[k] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
            }
        }

        /** Indices of sharp direction changes (> ~60 degrees) of the resampled swipe. */
        fun findCorners(px: FloatArray, py: FloatArray, m: Int): IntArray {
            val w = 2
            val out = ArrayList<Int>()
            var lastAngle = 0f
            for (i in w until m - w) {
                val ax = px[i] - px[i - w]
                val ay = py[i] - py[i - w]
                val bx = px[i + w] - px[i]
                val by = py[i + w] - py[i]
                val la = sqrt(ax * ax + ay * ay)
                val lb = sqrt(bx * bx + by * by)
                if (la < 0.12f || lb < 0.12f) continue
                val cos = (ax * bx + ay * by) / (la * lb)
                if (cos > 0.5f) continue
                val angle = 1f - cos
                if (out.isNotEmpty() && i - out[out.size - 1] <= w) {
                    if (angle > lastAngle) { out[out.size - 1] = i; lastAngle = angle }
                    continue
                }
                out.add(i)
                lastAngle = angle
            }
            return out.toIntArray()
        }

        /**
         * Letters (as a bit mask) on which the finger drew a small loop. A loop is an optional hint
         * for a doubled letter; people usually just glide over "ll" in "hello".
         */
        fun findLoops(xs: FloatArray, ys: FloatArray, n: Int, geo: Geometry): Int {
            var mask = 0
            var runLetter = -1
            var runStart = 0
            fun close(endExclusive: Int) {
                if (runLetter < 0 || endExclusive - runStart < 4) return
                var arc = 0f
                var turning = 0f
                var prevAngle = Float.NaN
                for (i in runStart + 1 until endExclusive) {
                    val dx = xs[i] - xs[i - 1]
                    val dy = ys[i] - ys[i - 1]
                    val l = sqrt(dx * dx + dy * dy)
                    if (l < 0.03f) continue
                    arc += l
                    val a = atan2(dy, dx)
                    if (!prevAngle.isNaN()) {
                        var d = a - prevAngle
                        while (d > Math.PI.toFloat()) d -= 2f * Math.PI.toFloat()
                        while (d < -Math.PI.toFloat()) d += 2f * Math.PI.toFloat()
                        turning += abs(d)
                    }
                    prevAngle = a
                }
                if (arc >= LOOP_MIN_ARC && turning >= LOOP_MIN_TURNING) mask = mask or (1 shl runLetter)
            }
            for (i in 0 until n) {
                var best = -1
                var bd = LOOP_RADIUS
                for (c in 0 until 26) {
                    if (!geo.present[c]) continue
                    val d = dist(xs[i], ys[i], geo.x[c], geo.y[c])
                    if (d <= bd) { bd = d; best = c }
                }
                if (best != runLetter) {
                    close(i)
                    runLetter = best
                    runStart = i
                }
            }
            close(n)
            return mask
        }
    }
}
