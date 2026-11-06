package com.noircipher.shade

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

class RareWordIndex private constructor(
    private val codes: HashMap<Char, Int>,
    private val data: ByteArray,
    private val starts: IntArray,
    private val ranks: IntArray,
    private val firstRank: Int
) {
    val size: Int get() = starts.size - 1

    constructor(entries: List<Pair<String, Int>>, firstRank: Int) : this(Packed(entries), firstRank)
    private constructor(p: Packed, firstRank: Int) : this(p.codes, p.data, p.starts, p.ranks, firstRank)

    /** Save the compiled index for fast loading next time. */
    fun save(file: File) {
        java.io.DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(CACHE_VERSION)
            o.writeInt(codes.size)
            for ((c, i) in codes) { o.writeChar(c.code); o.writeInt(i) }
            o.writeInt(data.size)
            o.write(data)
            o.writeInt(ranks.size)
            val b = ByteBuffer.allocate((starts.size + ranks.size) * 4)
            b.asIntBuffer().also { it.put(starts); it.put(ranks) }
            o.write(b.array())
        }
    }

    /** Rank of [w] in the frequency list, or -1 if not found. */
    fun rankOf(w: String): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, w)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return firstRank + ranks[mid]
            }
        }
        return -1
    }

    /** Does [w] exist in the rare words index? */
    fun contains(w: String): Boolean = rankOf(w) >= 0

    /** Top completions for a prefix. */
    fun completions(prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = ByteArray(prefix.length) { i -> (codes[prefix[i]] ?: return emptyList()).toByte() }
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (below(mid, p)) lo = mid + 1 else hi = mid
        }
        val top = java.util.PriorityQueue<Int>(compareByDescending { ranks[it] })
        var n = lo
        while (n < size && hasPrefix(n, p)) {
            if (starts[n + 1] - starts[n] > p.size) {
                top.add(n)
                if (top.size > limit * 3) top.poll()
            }
            n++
        }
        val letters = CharArray(codes.size + 1)
        for ((c, i) in codes) letters[i] = c
        return top.sortedBy { ranks[it] }.map { i ->
            val sb = StringBuilder()
            for (k in starts[i] until starts[i + 1]) sb.append(letters[data[k].toInt() and 0xFF])
            sb.toString() to firstRank + ranks[i]
        }.take(limit)
    }

    private fun below(n: Int, p: ByteArray): Boolean {
        val s = starts[n]
        val len = starts[n + 1] - s
        for (i in 0 until min(len, p.size)) {
            val d = (data[s + i].toInt() and 0xFF) - (p[i].toInt() and 0xFF)
            if (d != 0) return d < 0
        }
        return len < p.size
    }

    private fun hasPrefix(n: Int, p: ByteArray): Boolean {
        val s = starts[n]
        if (starts[n + 1] - s < p.size) return false
        for (i in p.indices) if (data[s + i] != p[i]) return false
        return true
    }

    private fun compareAt(n: Int, w: String): Int {
        val s = starts[n]
        val len = starts[n + 1] - s
        val m = min(len, w.length)
        for (i in 0 until m) {
            val c = codes[w[i]] ?: return -1
            val d = (data[s + i].toInt() and 0xFF) - c
            if (d != 0) return d
        }
        return len - w.length
    }

    private class Packed(entries: List<Pair<String, Int>>) {
        val codes = HashMap<Char, Int>()
        val data: ByteArray
        val starts: IntArray
        val ranks: IntArray

        init {
            val chars = HashSet<Char>()
            var total = 0
            for ((w, _) in entries) {
                total += w.length
                for (c in w) chars.add(c)
            }
            chars.sorted().take(254).forEachIndexed { i, c -> codes[c] = i + 1 }
            val ok = entries.filter { (w, _) -> w.all { codes.containsKey(it) } }
            data = ByteArray(total)
            starts = IntArray(ok.size + 1)
            ranks = IntArray(ok.size)
            var k = 0
            ok.forEachIndexed { n, (w, r) ->
                starts[n] = k
                ranks[n] = r
                for (c in w) data[k++] = codes.getValue(c).toByte()
            }
            starts[ok.size] = k
        }
    }

    companion object {
        private const val CACHE_VERSION = 1

        fun load(file: File, firstRank: Int): RareWordIndex? = runCatching {
            java.io.DataInputStream(file.inputStream().buffered(1 shl 16)).use { i ->
                if (i.readInt() != CACHE_VERSION) return null
                val codes = HashMap<Char, Int>()
                repeat(i.readInt()) { val c = i.readChar(); codes[c] = i.readInt() }
                val data = ByteArray(i.readInt()).also { i.readFully(it) }
                val n = i.readInt()
                val bytes = ByteArray((n + 1 + n) * 4).also { i.readFully(it) }
                val ints = ByteBuffer.wrap(bytes).asIntBuffer()
                val starts = IntArray(n + 1).also { ints.get(it) }
                val ranks = IntArray(n).also { ints.get(it) }
                RareWordIndex(codes, data, starts, ranks, firstRank)
            }
        }.getOrNull()

        /** Parse "word rank36" lines → (word, rank) pairs. */
        fun parse(lines: List<String>): List<Pair<String, Int>> = lines.mapNotNull { l ->
            val sp = l.indexOf(' ')
            if (sp <= 0) null else l.substring(0, sp) to (l.substring(sp + 1).trim().toIntOrNull(36) ?: return@mapNotNull null)
        }
    }
}
