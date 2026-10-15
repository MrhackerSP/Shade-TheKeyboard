package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import kotlin.math.abs

/**
 * Offline dictionaries with:
 *  - a Trie for O(M) prefix lookups
 *  - a SymSpell-style delete-1 index for O(1) correction lookups
 *  - a memory-mapped binary format compiled on first use
 *  - coroutine-friendly async API
 */
class WordDictionary(private val ctx: Context) {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var isWord = false
        var rank = Int.MAX_VALUE
    }

    private class Dict(
        val root: TrieNode,
        val total: Int,
        val deletes: HashMap<String, String>   // symspell delete-1 index
    )

    private var active: List<Dict> = emptyList()
    private var activeIds: Set<String> = emptySet()

    private val prefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Int> = HashMap()

    // LRU cache on corrections (belt + braces on top of symspell)
    private val correctionCache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > 256
    }

    init { reloadUser() }

    fun configure(ids: Set<String>) {
        val wanted = if (ids.isEmpty()) setOf("en") else ids
        if (wanted == activeIds) return
        activeIds = wanted
        active = wanted.sorted().mapNotNull { load(it) }
    }

    // ------------------------------------------------------------------ loading

    private fun load(id: String): Dict? {
        val binFile = binFileFor(id)
        if (!binFile.exists() || binFile.lastModified() < srcLastModified(id)) {
            if (!compileToBinary(id, binFile)) return null
        }
        return try {
            mapBinary(binFile)
        } catch (_: Exception) {
            null
        }
    }

    private fun srcLastModified(id: String): Long = try {
        if (id.startsWith("file:")) File(dictDir(ctx), id.removePrefix("file:")).lastModified()
        else 0L
    } catch (_: Exception) { 0L }

    private fun binFileFor(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(ctx.cacheDir, "dict_$safe.bin")
    }

    private fun readSource(id: String): List<String>? = try {
        val lines = if (id.startsWith("file:")) {
            File(dictDir(ctx), id.removePrefix("file:")).readLines()
        } else {
            ctx.assets.open("words_$id.txt").bufferedReader().use { it.readLines() }
        }
        lines.map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Compile a text word list into a compact binary file:
     *   [int32 count][for each: int16 len][utf8 bytes]
     * Order is preserved (frequency-ranked), so rank = index.
     */
    private fun compileToBinary(id: String, out: File): Boolean {
        val words = readSource(id) ?: return false
        return try {
            out.parentFile?.mkdirs()
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                val ch = raf.channel
                val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(words.size)
                header.flip()
                ch.write(header)
                val buf = ByteBuffer.allocate(2 + 64).order(ByteOrder.LITTLE_ENDIAN)
                for (w in words) {
                    val bytes = w.toByteArray(Charsets.UTF_8)
                    if (bytes.size > 0xFFFF) continue
                    buf.clear()
                    buf.putShort(bytes.size.toShort())
                    buf.put(bytes)
                    buf.flip()
                    ch.write(buf)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Memory-map the binary and build the Trie + SymSpell index once. */
    private fun mapBinary(file: File): Dict? {
        val raf = RandomAccessFile(file, "r")
        val ch = raf.channel
        val size = ch.size()
        if (size < 4) { raf.close(); return null }
        val map: ByteBuffer = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)

        val count = map.getInt(0)
        val root = TrieNode()
        val deletes = HashMap<String, String>(count * 8)

        var pos = 4
        for (rank in 0 until count) {
            if (pos + 2 > size) break
            val len = map.getShort(pos).toInt() and 0xFFFF
            pos += 2
            if (pos + len > size) break
            val bytes = ByteArray(len)
            map.get(bytes, pos, len)
            pos += len
            val w = String(bytes, Charsets.UTF_8)

            // Insert into Trie
            var node = root
            for (c in w) {
                node = node.children.getOrPut(c) { TrieNode() }
            }
            if (!node.isWord) {
                node.isWord = true
                node.rank = rank
            }

            // SymSpell delete-1 index
            indexDeletes(w, rank, deletes)
        }
        return Dict(root, count, deletes)
    }

    /** Generate all strings one delete away and store the first (lowest-rank) source. */
    private fun indexDeletes(word: String, rank: Int, out: HashMap<String, String>) {
        // deletes
        for (i in word.indices) {
            val d = word.substring(0, i) + word.substring(i + 1)
            if (out.putIfAbsent(d, word) == null) { /* first wins (rank order) */ }
        }
        // transposes
        for (i in 0 until word.length - 1) {
            val t = word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
            out.putIfAbsent(t, word)
        }
        // substitutions via a..z would blow up memory 26x; the query side handles those.
    }

    // ------------------------------------------------------------------ user words

    fun reloadUser() {
        val m = HashMap<String, Int>()
        for ((k, v) in prefs.all) if (v is Int) m[k] = v
        user = m
    }

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        if (user.containsKey(w)) return true
        for (d in active) if (trieContains(d.root, w)) return true
        return false
    }

    private fun trieContains(root: TrieNode, w: String): Boolean {
        var node = root
        for (c in w) node = node.children[c] ?: return false
        return node.isWord
    }

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        if (contains(w)) return
        val n = (user[w] ?: 0) + 1
        user[w] = n
        prefs.edit().putInt(w, n).apply()
    }

    // ------------------------------------------------------------------ sync API

    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashSet<String>(max * 2)

        // user words first (prefix scan on the small user map)
        user.entries.asSequence()
            .filter { it.key.startsWith(p) }
            .sortedByDescending { it.value }
            .take(max)
            .forEach { out.add(it.key) }

        // trie descent
        for (d in active) {
            var node = d.root
            var ok = true
            for (c in p) {
                node = node.children[c] ?: run { ok = false; TrieNode() }
                if (!ok) break
            }
            if (!ok) continue
            collectPrefix(node, StringBuilder(p), out, max)
            if (out.size >= max) break
        }
        return out.take(max).map { match(prefix, it) }
    }

    private fun collectPrefix(node: TrieNode, sb: StringBuilder, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return
        if (node.isWord) out.add(sb.toString())
        for ((c, child) in node.children) {
            if (out.size >= max) return
            sb.append(c)
            collectPrefix(child, sb, out, max)
            sb.setLength(sb.length - 1)
        }
    }

    fun correction(word: String): String? {
        val w = word.lowercase()
        if (w.length < 3 || contains(w) || !w.all { it.isLetter() || it == '\'' }) return null
        if (correctionCache.containsKey(w)) return correctionCache[w]

        // 1. symspell lookup: exact delete/transpose hits
        for (d in active) {
            d.deletes[w]?.let { hit ->
                val m = match(word, hit)
                correctionCache[w] = m
                return m
            }
        }
        // 2. single substitution: try replacing each position with a..z and check the trie
        for (d in active) {
            var i = 0
            while (i < w.length) {
                var c = 'a'
                while (c <= 'z') {
                    if (c != w[i]) {
                        val cand = w.substring(0, i) + c + w.substring(i + 1)
                        if (trieContains(d.root, cand)) {
                            val m = match(word, cand)
                            correctionCache[w] = m
                            return m
                        }
                    }
                    c++
                }
                i++
            }
        }
        correctionCache[w] = null
        return null
    }

    private fun match(typed: String, w: String): String {
        if (w == "i" || w.startsWith("i'")) return "I" + w.substring(1)
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> w.uppercase()
            typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
    }

    // ------------------------------------------------------------------ async API

    suspend fun suggestAsync(prefix: String, max: Int): List<String> =
        withContext(Dispatchers.Default) { suggest(prefix, max) }

    suspend fun correctionAsync(word: String): String? =
        withContext(Dispatchers.Default) { correction(word) }

    // ------------------------------------------------------------------ user data

    fun exportUser(): Map<String, Int> = HashMap(user)

    companion object {
        fun dictDir(ctx: Context): File = File(ctx.filesDir, "dicts").apply { mkdirs() }

        fun clearLearned(ctx: Context) {
            ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear().apply()
        }

        fun listImported(ctx: Context): List<String> =
            dictDir(ctx).listFiles()?.map { it.name }?.sorted() ?: emptyList()

        fun deleteImported(ctx: Context, name: String) {
            File(dictDir(ctx), name).delete()
        }

        fun importFile(ctx: Context, uri: Uri, displayName: String): String? {
            return try {
                val entries = ArrayList<Pair<String, Int>>()
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                    var n = 0
                    for (line in r.lineSequence()) {
                        if (++n > 300_000) break
                        val parts = line.trim().split(Regex("[\\t ,]+"))
                        val w = parts.firstOrNull() ?: continue
                        if (w.length in 1..30 && w.all { it.isLetter() || it == '\'' || it == '-' }) {
                            entries.add(w.lowercase() to (parts.getOrNull(1)?.toIntOrNull() ?: 0))
                        }
                    }
                } ?: return null
                if (entries.isEmpty()) return null
                if (entries.any { it.second > 0 }) entries.sortByDescending { it.second }
                var name = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "dictionary" }
                if (!name.endsWith(".txt")) name += ".txt"
                File(dictDir(ctx), name).writeText(entries.joinToString("\n") { it.first })
                "file:$name"
            } catch (_: Exception) {
                null
            }
        }
    }
}
