package com.shade.keyboard.slide

import kotlin.math.ln

class GlideLexicon private constructor(
    val words: Array<String>,
    val codes: Array<ByteArray>,
    val prior: FloatArray,
    val doubleMask: IntArray
) {
    val size: Int get() = words.size

    companion object {
        const val MAX_LETTERS = 24
        val EMPTY = GlideLexicon(emptyArray(), emptyArray(), FloatArray(0), IntArray(0))

        fun codeOf(c: Char): Int = when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            else -> -1
        }

        fun build(words: List<String>, priors: FloatArray): GlideLexicon {
            require(words.size == priors.size) { "one prior per word" }
            val outWords = ArrayList<String>(words.size)
            val outCodes = ArrayList<ByteArray>(words.size)
            val outPrior = FloatArray(words.size)
            val outDouble = IntArray(words.size)
            var out = 0
            val seen = HashSet<String>(words.size * 2)
            val buf = ByteArray(64)
            for (i in words.indices) {
                val w = words[i]
                if (!seen.add(w)) continue
                var n = 0
                var dbl = 0
                var sep = false
                var ok = w.length in 2..40
                if (ok) {
                    for (ch in w) {
                        if (ch == '\'' || ch == '-') { sep = true; continue }
                        val c = codeOf(ch)
                        if (c < 0 || n >= buf.size) { ok = false; break }
                        if (n > 0 && buf[n - 1].toInt() == c) {
                            if (!sep) dbl = dbl or (1 shl c)
                        } else {
                            buf[n++] = c.toByte()
                        }
                        sep = false
                    }
                }
                if (!ok || n < 2 || n > MAX_LETTERS) continue
                outWords.add(w)
                outCodes.add(buf.copyOf(n))
                outPrior[out] = priors[i].coerceIn(0f, 1f)
                outDouble[out] = dbl
                out++
            }
            return GlideLexicon(
                outWords.toTypedArray(),
                outCodes.toTypedArray(),
                outPrior.copyOf(out),
                outDouble.copyOf(out)
            )
        }

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
