package com.noircipher.shade

import java.util.Arrays

class BundledBigramModel(lines: List<String>) {

    private val vocab: Array<String>
    private val starts: IntArray
    private val prevs: IntArray
    private val nexts: IntArray
    private val weights: ByteArray

    init {
        val all = java.util.TreeSet<String>()
        val rows = lines.mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 } }
        for (r in rows) {
            all.add(r[0])
            for (i in 1 until r.size) all.add(r[i].substringBefore('/'))
        }
        vocab = all.toTypedArray()
        val sorted = rows.sortedBy { it[0] }
        val total = sorted.sumOf { it.size - 1 }
        prevs = IntArray(sorted.size)
        starts = IntArray(sorted.size + 1)
        nexts = IntArray(total)
        weights = ByteArray(total)
        var k = 0
        sorted.forEachIndexed { n, r ->
            prevs[n] = Arrays.binarySearch(vocab, r[0])
            starts[n] = k
            for (i in 1 until r.size) {
                val cut = r[i].lastIndexOf('/')
                // Skip malformed tokens (no "/weight" suffix, or an empty word): substring(0, -1)
                // would throw and discard the WHOLE bigram table for this language.
                if (cut <= 0) continue
                val id = Arrays.binarySearch(vocab, r[i].substring(0, cut))
                if (id < 0) continue
                nexts[k] = id
                weights[k] = (r[i].substring(cut + 1).toIntOrNull() ?: 40).toByte()
                k++
            }
        }
        starts[sorted.size] = k
    }

    private fun row(prev: String): Int {
        val id = Arrays.binarySearch(vocab, prev)
        return if (id < 0) -1 else Arrays.binarySearch(prevs, id)
    }

    /** −ln P(w | prev), or -1 if the pair is unknown. Lower = more likely. */
    fun cost(prev: String, w: String): Float {
        val r = row(prev)
        if (r < 0) return -1f
        val id = Arrays.binarySearch(vocab, w)
        if (id < 0) return -1f
        for (i in starts[r] until starts[r + 1]) if (nexts[i] == id) return weights[i] / 2f
        return -1f
    }

    fun known(prev: String): Boolean = row(prev) >= 0

    /** Next words after [prev], sorted by descending probability. */
    fun next(prev: String): List<String> {
        val r = row(prev)
        if (r < 0) return emptyList()
        return (starts[r] until starts[r + 1]).map { vocab[nexts[it]] }
    }
}
