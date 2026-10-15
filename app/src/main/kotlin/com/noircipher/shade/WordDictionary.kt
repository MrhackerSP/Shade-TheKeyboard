package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import kotlin.math.abs

/**
 * Offline dictionaries. Several can be active at once (multilingual typing):
 * bundled assets ("en", "es", "de", "fr") and imported files ("file:<name>").
 * Word lists are ordered by frequency. Learned words are stored on-device only.
 */
class WordDictionary(private val ctx: Context) {
    private val cache = HashMap<String, List<String>>()
    private var active: List<List<String>> = emptyList()
    private var known: HashSet<String> = HashSet()
    private var activeIds: Set<String> = emptySet()

    private val prefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Int> = HashMap()

    init { reloadUser() }

    fun configure(ids: Set<String>) {
        val wanted = if (ids.isEmpty()) setOf("en") else ids
        if (wanted == activeIds) return
        activeIds = wanted
        active = wanted.sorted().mapNotNull { load(it) }
        val k = HashSet<String>()
        active.forEach { k.addAll(it) }
        known = k
    }

    private fun load(id: String): List<String>? = cache.getOrPut(id) {
        try {
            val lines = if (id.startsWith("file:")) {
                File(dictDir(ctx), id.removePrefix("file:")).readLines()
            } else {
                ctx.assets.open("words_$id.txt").bufferedReader().use { it.readLines() }
            }
            lines.map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun reloadUser() {
        val m = HashMap<String, Int>()
        for ((k, v) in prefs.all) if (v is Int) m[k] = v
        user = m
    }

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return w in known || user.containsKey(w)
    }

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        if (w in known && !user.containsKey(w)) return
        val n = (user[w] ?: 0) + 1
        user[w] = n
        prefs.edit().putInt(w, n).apply()
    }

    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashSet<String>()
        user.entries.filter { it.key.startsWith(p) }
            .sortedByDescending { it.value }
            .forEach { if (out.size < max) out.add(it.key) }
        val per = active.map { d -> d.asSequence().filter { it.startsWith(p) }.take(max).toList() }
        var i = 0
        while (out.size < max && per.any { i < it.size }) {
            for (list in per) if (i < list.size && out.size < max) out.add(list[i])
            i++
        }
        return out.map { match(prefix, it) }.distinct()
    }

    /** Best single-edit correction for an unknown word, or null. */
    fun correction(word: String): String? {
        val w = word.lowercase()
        if (w.length < 3 || contains(w) || !w.all { it.isLetter() || it == '\'' }) return null
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (d in active) {
            for ((rank, c) in d.withIndex()) {
                if (rank >= bestRank) break
                if (c.isNotEmpty() && c[0] == w[0] && within1(w, c)) {
                    best = c
                    bestRank = rank
                    break
                }
            }
        }
        return best?.let { match(word, it) }
    }

    private fun within1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (a == b || abs(la - lb) > 1) return false
        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return false
            if (a.substring(i + 1) == b.substring(i + 1)) return true            // substitution
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] &&
                a.substring(i + 2) == b.substring(i + 2)                        // transposition
        }
        val s = if (la < lb) a else b
        val l = if (la < lb) b else a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)                              // insertion / deletion
    }

    private fun match(typed: String, w: String): String {
        if (w == "i" || w.startsWith("i'")) return "I" + w.substring(1)
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> w.uppercase()
            typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
    }

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

        /** Import a plain-text word list (one word per line, optional "<TAB>frequency"). */
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
