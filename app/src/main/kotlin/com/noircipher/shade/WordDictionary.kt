package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory dictionary with a prefix index for O(k) suggestions and
 * a prefix-bucket fuzzy scan for O(k) corrections.
 *
 * The prefix index maps 1- and 2-character prefixes to words starting
 * with them, sorted by frequency descending. Suggestions for a prefix
 * `p` become `index[p.take(2)].filter { it.startsWith(p) }`, which is
 * far cheaper than scanning the entire word set on every keystroke.
 *
 * Correction uses the same 2-char bucket as a filter, then checks edit
 * distance 1 within that bucket. Fast enough for a 50k-word dictionary
 * on mid-range devices.
 */
class WordDictionary(private val ctx: Context) {

    private val words: MutableMap<String, Long> = HashMap()
    private val prefix1: MutableMap<String, MutableList<String>> = HashMap()
    private val prefix2: MutableMap<String, MutableList<String>> = HashMap()
    private var loadedId: String? = null

    private val prefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Float> = HashMap()
    private val userPrefix1: MutableMap<String, MutableList<String>> = HashMap()
    private val userPrefix2: MutableMap<String, MutableList<String>> = HashMap()

    var bigramModel: BigramModel? = null

    private val contractions: Map<String, String> = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
        "werent" to "weren't", "hasnt" to "hasn't", "havent" to "haven't",
        "hadnt" to "hadn't", "didnt" to "didn't", "doesnt" to "doesn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "weve" to "we've",
        "its" to "it's", "thats" to "that's", "whats" to "what's",
        "heres" to "here's", "theres" to "there's", "wheres" to "where's",
        "hes" to "he's", "shes" to "she's", "lets" to "let's"
    )

    init { reloadUser() }

    suspend fun configure(localeId: String = "en") {
        if (loadedId == localeId) return
        loadedId = localeId
        withContext(Dispatchers.IO) {
            try {
                val w = HashMap<String, Long>()
                for (id in localeId.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    try {
                        readWordList(id, w)
                    } catch (_: Exception) {
                        // missing list: skip it
                    }
                }
                val top = if (w.size > MAX_WORDS) {
                    w.entries.sortedByDescending { it.value }.take(MAX_WORDS)
                } else w.entries.toList()
                words.clear()
                for (e in top) words[e.key] = e.value
                rebuildPrefixIndex()
            } catch (_: Exception) {
                // No dictionary file
            }
        }
    }

    private fun readWordList(id: String, w: MutableMap<String, Long>) {
        val candidates = listOf(
            "languages/$id/dictionary.txt",
            "wordlists/$id.txt"
        )
        var lastError: Exception? = null
        for (path in candidates) {
            try {
                val raw = java.io.BufferedInputStream(ctx.assets.open(path))
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val stream: java.io.InputStream =
                    if (b0 == 0x1f && b1 == 0x8b) java.util.zip.GZIPInputStream(raw) else raw
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val t = line.trim()
                        if (t.isEmpty()) continue
                        val sp = t.indexOfFirst { it == ' ' || it == '\t' }
                        val word = (if (sp < 0) t else t.substring(0, sp)).lowercase()
                        val freq = if (sp < 0) 1L else (t.substring(sp + 1).trim().toLongOrNull() ?: continue)
                        if (word.length > 20) continue
                        if (word.length == 1 && word[0] in 'a'..'z' && word != "a" && word != "i") continue
                        if (!word.all { isWordChar(it) }) continue
                        w[word] = maxOf(w[word] ?: 0L, freq)
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No dictionary asset found for $id")
    }

    private fun rebuildPrefixIndex() {
        prefix1.clear()
        prefix2.clear()
        for ((word, freq) in words) {
            if (word.isEmpty()) continue
            prefix1.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
            if (word.length >= 2) {
                prefix2.getOrPut(word.substring(0, 2)) { ArrayList() }.add(word)
            }
        }
        for (list in prefix1.values) list.sortByDescending { words[it] ?: 0L }
        for (list in prefix2.values) list.sortByDescending { words[it] ?: 0L }
    }

    private fun rebuildUserPrefixIndex() {
        userPrefix1.clear()
        userPrefix2.clear()
        for ((word, _) in user) {
            if (word.isEmpty()) continue
            userPrefix1.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
            if (word.length >= 2) {
                userPrefix2.getOrPut(word.substring(0, 2)) { ArrayList() }.add(word)
            }
        }
        for (list in userPrefix1.values) list.sortByDescending { user[it] ?: 0f }
        for (list in userPrefix2.values) list.sortByDescending { user[it] ?: 0f }
    }

    fun reloadUser() {
        val m = HashMap<String, Float>()
        for ((k, v) in prefs.all) {
            when (v) {
                is Float -> m[k] = v
                is Int -> m[k] = v.toFloat()
            }
        }
        user = m
        rebuildUserPrefixIndex()
    }

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return user.containsKey(w) || words.containsKey(w)
    }

    fun learn(word: String, prev2: String? = null, prev1: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { isWordChar(it) }) return
        val old = user[w] ?: 0f
        val score = old * DECAY + 1f
        user[w] = score
        prefs.edit().putFloat(w, score).apply()

        if (w.isNotEmpty()) {
            val k1 = w.substring(0, 1)
            val l1 = userPrefix1.getOrPut(k1) { ArrayList() }
            if (w !in l1) l1.add(w)
            if (w.length >= 2) {
                val k2 = w.substring(0, 2)
                val l2 = userPrefix2.getOrPut(k2) { ArrayList() }
                if (w !in l2) l2.add(w)
            }
        }
        bigramModel?.observe(prev2, prev1, w)
    }

    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val known = contains(p)
        val scored = HashMap<String, Float>()

        contractions[p]?.let { scored[it.lowercase()] = if (known) 12f else 100f }

        val userBucket: List<String>? = if (p.length >= 2) {
            userPrefix2[p.substring(0, 2)]
        } else {
            userPrefix1[p.substring(0, 1)]
        }
        var found = 0
        userBucket?.let { bucket ->
            for (cand in bucket) {
                if (!cand.startsWith(p)) continue
                val us = user[cand] ?: continue
                val s = 16f + kotlin.math.ln(us + 1f) - 0.3f * (cand.length - p.length)
                scored[cand] = maxOf(scored[cand] ?: -1000f, s)
                if (++found >= 6) break
            }
        }

        var completions = 0
        val bucket: List<String>? = if (p.length >= 2) {
            prefix2[p.substring(0, 2)]
        } else {
            prefix1[p.substring(0, 1)]
        }
        bucket?.let { b ->
            for (cand in b) {
                if (!cand.startsWith(p)) continue
                val f = words[cand] ?: continue
                val s = kotlin.math.ln(f.toDouble()).toFloat() - 0.3f * (cand.length - p.length)
                scored[cand] = maxOf(scored[cand] ?: -1000f, s)
                if (++completions >= 12) break
            }
        }

        if (p.length in 3..20 && (!known || completions < max)) {
            val limit = if (p.length <= 4) 1.0f else 1.6f
            val fuzzyBucket = prefix1[p.substring(0, 1)]
            if (fuzzyBucket != null) {
                val d = Array(24) { FloatArray(24) }
                for (cand in fuzzyBucket) {
                    if (cand.length < p.length - 1 || cand.length > p.length + 1) continue
                    if (scored.containsKey(cand)) continue
                    val cost = editCost(p, cand, d)
                    if (cost > limit) continue
                    val f = words[cand] ?: continue
                    scored[cand] = kotlin.math.ln(f.toDouble()).toFloat() - 4f * cost
                }
            }
        }

        return scored.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { match(prefix, it.key) }
    }

    private val adj: Array<BooleanArray> = Array(26) { BooleanArray(26) }

    init {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r1 in rows.indices) for (c1 in rows[r1].indices) {
            for (r2 in rows.indices) for (c2 in rows[r2].indices) {
                if ((r1 != r2 || c1 != c2) &&
                    kotlin.math.abs(r1 - r2) <= 1 && kotlin.math.abs(c1 - c2) <= 1
                ) {
                    adj[rows[r1][c1] - 'a'][rows[r2][c2] - 'a'] = true
                }
            }
        }
    }

    /** Typo distance: neighbouring keys and swapped letters cost less than random mistakes. */
    private fun editCost(a: String, b: String, d: Array<FloatArray>): Float {
        val n = a.length
        val m = b.length
        for (i in 0..n) d[i][0] = i.toFloat()
        for (j in 0..m) d[0][j] = j.toFloat()
        for (i in 1..n) {
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0f
                    ca in 'a'..'z' && cb in 'a'..'z' && adj[ca - 'a'][cb - 'a'] -> 0.6f
                    else -> 1f
                }
                var v = minOf(d[i - 1][j] + 1f, d[i][j - 1] + 1f, d[i - 1][j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    v = minOf(v, d[i - 2][j - 2] + 0.7f)
                }
                d[i][j] = v
            }
        }
        return d[n][m]
    }

    fun correction(word: String): String? {
        val w = word.lowercase()

        contractions[w]?.let { return match(word, it) }

        if (w.length < 3 || !w.all { isWordChar(it) }) return null
        if (w.any { it.code > 0x24F }) return null
        if (contains(w)) return null

        if (w.length > 20) return null
        val candidates: Iterable<String> = prefix1[w.substring(0, 1)] ?: emptyList()

        var best: String? = null
        var bestScore = -1000f
        val d = Array(24) { FloatArray(24) }
        for (cand in candidates) {
            if (cand.length !in (w.length - 1)..(w.length + 1)) continue
            val freq = words[cand] ?: continue
            if (!within1(w, cand)) continue
            val score = kotlin.math.ln(freq.toDouble()).toFloat() - 3f * editCost(w, cand, d)
            if (score > bestScore) {
                best = cand
                bestScore = score
            }
        }
        return best?.let { match(word, it) }
    }

    private fun within1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (a == b || kotlin.math.abs(la - lb) > 1) return false
        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return false
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] &&
                a.substring(i + 2) == b.substring(i + 2)
        }
        val s = if (la < lb) a else b
        val l = if (la < lb) b else a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }

    private fun match(typed: String, w: String): String {
        if (w == "i" || w.startsWith("i'")) return "I" + w.substring(1)
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> w.uppercase()
            typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
    }

    fun predictNext(prev2: String?, prev1: String?, max: Int): List<String> {
        val model = bigramModel ?: return emptyList()
        return model.predict(prev2, prev1, max).map { it.first }
    }

    fun rerankWithContext(prev2: String?, prev1: String?, candidates: List<String>): List<String> {
        val model = bigramModel ?: return candidates
        return model.rerank(prev2, prev1, candidates)
    }

    suspend fun suggestAsync(prefix: String, max: Int): List<String> =
        withContext(Dispatchers.Default) { suggest(prefix, max) }

    suspend fun correctionAsync(word: String): String? =
        withContext(Dispatchers.Default) { correction(word) }

    fun exportUser(): Map<String, Float> = HashMap(user)

    companion object {
        private const val DECAY = 0.99f
        private const val MAX_WORDS = 300000

        /** Letters, apostrophes and combining marks (needed for Hindi vowel signs). */
        fun isWordChar(c: Char): Boolean {
            if (c == '\'' || c.isLetter()) return true
            val t = Character.getType(c)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
        }

        fun clearLearned(ctx: Context) {
            ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
