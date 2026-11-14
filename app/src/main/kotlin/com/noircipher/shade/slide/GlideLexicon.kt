package com.noircipher.shade.slide

import kotlin.math.ln

/**
 * Immutable, glide-ready view of a word list: every word as a short array of letter codes, so the
 * decoder can score tens of thousands of words per swipe without touching strings or allocating.
 *
 * Only words that can be swiped are kept: letters a-z (an apostrophe or hyphen is allowed and is
 * simply not part of the path, so "don't" is swiped as d-o-n-t) with at least two different
 * neighbouring letters. Consecutive repeats are collapsed, because a double letter is not a
 * separate stop on the swipe ("hello" is swiped h-e-l-o); which letters were doubled is kept in
 * [doubleMask] so a small loop drawn on a key can still favour the doubled spelling.
 */
class GlideLexicon private constructor(
    val words: Array<String>,
    /** Letter codes 0..25, consecutive duplicates collapsed. */
    val codes: Array<ByteArray>,
    /** 0..1, where 1 is the most common word. */
    val prior: FloatArray,
    /** Bit `c` is set when letter `c` appears twice in a row in the word. */
    val doubleMask: IntArray
) {
    val size: Int get() = words.size

    companion object {
        const val MAX_LETTERS = 24
        val EMPTY = GlideLexicon(emptyArray(), emptyArray(), FloatArray(0), IntArray(0))

        /** Letter code of [c] (a-z, case-insensitive) or -1. */
        fun codeOf(c: Char): Int = when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            else -> -1
        }

        /**
         * Builds a lexicon. [priors] has one entry per word (0..1, 1 = most common); entries for
         * words that cannot be swiped are ignored.
         */
        fun build(words: List<String>, priors: FloatArray): GlideLexicon {
            require(words.size == priors.size) { "one prior per word" }
            val outWords = ArrayList<String>(words.size)
            val outCodes = ArrayList<ByteArray>(words.size)
            val outPrior = ArrayList<Float>(words.size)
            val outDouble = ArrayList<Int>(words.size)
            val buf = ByteArray(64)
            for (i in words.indices) {
                val w = words[i]
                var n = 0
                var dbl = 0
                var ok = w.length in 2..40
                if (ok) {
                    for (ch in w) {
                        if (ch == '\'' || ch == '-') continue
                        val c = codeOf(ch)
                        if (c < 0 || n >= buf.size) { ok = false; break }
                        if (n > 0 && buf[n - 1].toInt() == c) {
                            dbl = dbl or (1 shl c)
                        } else {
                            buf[n++] = c.toByte()
                        }
                    }
                }
                if (!ok || n < 2 || n > MAX_LETTERS) continue
                outWords.add(w)
                outCodes.add(buf.copyOf(n))
                outPrior.add(priors[i].coerceIn(0f, 1f))
                outDouble.add(dbl)
            }
            return GlideLexicon(
                outWords.toTypedArray(),
                outCodes.toTypedArray(),
                outPrior.toFloatArray(),
                outDouble.toIntArray()
            )
        }

        /**
         * Maps raw frequency counts to 0..1 priors on a log scale, the most frequent word in
         * [counts] getting 1 and the least frequent 0.
         */
        fun priorsFromCounts(counts: LongArray): FloatArray {
            if (counts.isEmpty()) return FloatArray(0)
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            val logs = DoubleArray(counts.size) { ln(counts[it].coerceAtLeast(1L).toDouble()) }
            for (v in logs) { if (v < lo) lo = v; if (v > hi) hi = v }
            val span = hi - lo
            return FloatArray(counts.size) {
                if (span <= 1e-9) 0.5f else ((logs[it] - lo) / span).toFloat()
            }
        }
    }
}
