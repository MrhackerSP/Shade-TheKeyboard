package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simple in-memory dictionary. Loads Norvig's plain-text frequency list
 * from assets. Temporarily does not depend on SymSpellKt; swap in a
 * SymSpell-backed lookup once the API is confirmed.
 */
class WordDictionary(private val ctx: Context) {

    private val words: MutableMap<String, Long> = HashMap()
    private var loadedId: String? = null

    private val prefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Float> = HashMap()

    var bigramModel: BigramModel? = null

    init { reloadUser() }

    suspend fun configure(localeId: String = "en") {
        if (loadedId == localeId) return
        loadedId = localeId
        withContext(Dispatchers.IO) {
            try {
                ctx.assets.open("wordlists/${localeId}.txt").bufferedReader().useLines { lines ->
                    words.clear()
                    for (line in lines) {
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2) {
                            val freq = parts[1].toLongOrNull() ?: continue
                            words[parts[0].lowercase()] = freq
                        } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                            words[parts[0].lowercase()] = 1L
                        }
                    }
                }
            } catch (_: Exception) {
                // No dictionary file
            }
        }
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
    }

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return user.containsKey(w) || words.containsKey(w)
    }

    fun learn(word: String, prev2: String? = null, prev1: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        val old = user[w] ?: 0f
        val score = old * DECAY + 1f
        user[w] = score
        prefs.edit().putFloat(w, score).apply()
        bigramModel?.observe(prev2, prev1, w)
    }

    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashSet<String>(max * 2)

        user.entries.asSequence()
            .filter { it.key.startsWith(p) }
            .sortedByDescending { it.value }
            .take(max)
            .forEach { out.add(it.key) }

        if (out.size < max) {
            words.entries.asSequence()
                .filter { it.key.startsWith(p) }
                .sortedByDescending { it.value }
                .take(max - out.size)
                .forEach { out.add(it.key) }
        }
        return out.take(max).map { match(prefix, it) }
    }

    fun correction(word: String): String? {
        val w = word.lowercase()
        if (w.length < 3 || contains(w) || !w.all { it.isLetter() || it == '\'' }) return null

        // Simple bounded edit-distance-1 scan. Replace with SymSpellKt when wired up.
        var best: String? = null
        var bestFreq = 0L
        for ((cand, freq) in words) {
            if (cand.length !in (w.length - 1)..(w.length + 1)) continue
            if (cand.firstOrNull() != w.firstOrNull()) continue
            if (within1(w, cand) && freq > bestFreq) {
                best = cand
                bestFreq = freq
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

        fun clearLearned(ctx: Context) {
            ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
