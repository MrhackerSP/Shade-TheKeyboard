package com.noircipher.shade

import kotlin.math.min

/**
 * SymSpell-style symmetric deletion index for O(1) typo lookup.
 *
 * For the N most frequent words, pre-computes all 1-letter-deletion variants.
 * A typo is found by looking up the typed word and all its deletions in a
 * sorted array — a few binary searches instead of scanning thousands of words.
 *
 * Ported from Expressive Keyboard's SymSpell implementation.
 */
class SymSpellIndex(words: List<String>) {

    private val vocab: Array<String> = words.toTypedArray()
    private val rank: HashMap<String, Int> = HashMap<String, Int>(words.size * 2).also { m ->
        words.forEachIndexed { i, w -> m.putIfAbsent(w, i) }
    }

    private val deletes: LongArray = run {
        val n = min(vocab.size, MAX_WORDS)
        var count = 0
        for (i in 0 until n) {
            if (vocab[i].length <= MAX_LEN) count += vocab[i].length + 1
        }
        val out = LongArray(count)
        var k = 0
        for (i in 0 until n) {
            val w = vocab[i]
            if (w.length > MAX_LEN) continue
            out[k++] = pack(hashSkipping(w, -1, -1), i)
            for (j in w.indices) out[k++] = pack(hashSkipping(w, j, -1), i)
        }
        out.sort()
        out
    }

    /**
     * Word indices that are within 1-2 deletions of [lower].
     * Returns a set of indices into [vocab].
     */
    fun similar(lower: String): Set<Int> {
        val out = HashSet<Int>()
        val n = lower.length
        if (n == 0 || n > MAX_LEN + 2) return out
        lookup(hashSkipping(lower, -1, -1), out)
        for (a in 0 until n) {
            lookup(hashSkipping(lower, a, -1), out)
            if (n >= 5) {
                for (b in a + 1 until n) lookup(hashSkipping(lower, a, b), out)
            }
        }
        return out
    }

    /** Only exact match and 1 deletion — cheaper, for suggestion candidates. */
    fun similarNear(lower: String): Set<Int> {
        val out = HashSet<Int>()
        val n = lower.length
        if (n == 0 || n > MAX_LEN + 2) return out
        lookup(hashSkipping(lower, -1, -1), out)
        for (a in 0 until n) lookup(hashSkipping(lower, a, -1), out)
        return out
    }

    fun wordAt(index: Int): String = vocab[index]
    fun rankOf(index: Int): Int = index
    val size: Int get() = vocab.size

    private fun lookup(hash: Int, out: MutableSet<Int>) {
        val key = hash.toLong() shl 32
        var lo = 0
        var hi = deletes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (deletes[mid] < key) lo = mid + 1 else hi = mid
        }
        while (lo < deletes.size && (deletes[lo] ushr 32).toInt() == hash) {
            out.add((deletes[lo] and 0xFFFFFFFFL).toInt())
            lo++
        }
    }

    companion object {
        private const val MAX_WORDS = 40_000
        private const val MAX_LEN = 20

        private fun pack(hash: Int, index: Int): Long = (hash.toLong() shl 32) or index.toLong()

        /** String.hashCode() skipping characters at positions a and b (-1 = don't skip). */
        private fun hashSkipping(s: String, a: Int, b: Int): Int {
            var h = 0
            for (i in s.indices) {
                if (i != a && i != b) h = 31 * h + s[i].code
            }
            return h
        }
    }
}
