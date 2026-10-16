package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import com.darkrockstudios.symspellkt.api.SymSpell
import com.darkrockstudios.symspellkt.common.SymSpellConfig
import com.darkrockstudios.symspellkt.exception.SymSpellException
import com.darkrockstudios.symspellkt.impl.SymSpellImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory dictionary with:
 *  - Prefix index for O(k) suggestions
 *  - SymSpellKt for O(1) fuzzy correction (deletion-index)
 *  - User-learned words with their own prefix index
 *
 * SymSpell handles misspelling lookups; the prefix index handles
 * prefix completion. They're separate fast paths and neither blocks
 * the other.
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

    /** SymSpell instance, built once after the dictionary loads. */
    @Volatile private var symSpell: SymSpell? = null

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
                ctx.assets.open("wordlists/${localeId}.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2) {
                            val freq = parts[1].toLongOrNull() ?: continue
                            w[parts[0].lowercase()] = freq
                        } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                            w[parts[0].lowercase()] = 1L
                        }
                    }
                }
                words.clear()
                words.putAll(w)
                rebuildPrefixIndex()
                buildSymSpell()
            } catch (_: Exception) {
                // No dictionary file
            }
        }
    }

    /**
     * Build the SymSpell deletion index over the loaded dictionary plus
     * the user's learned words. Runs once per locale load.
     */
    private fun buildSymSpell() {
        try {
            val config = SymSpellConfig(
                language = "en",
                maxDictionaryEditDistance = 2,
                prefixLength = 7,
                countThreshold = 1
            )
            val ss = SymSpellImpl(config)
            // Bulk-load dictionary terms. Frequency is used for tiebreaks.
            val dictionary = words.map { (word, freq) ->
                com.darkrockstudios.symspellkt.common.Suggestions(word, freq.toDouble())
            }
            ss.loadDictionary(dictionary)
            symSpell = ss
        } catch (_: SymSpellException) {
            symSpell = null
        } catch (_: Exception) {
            symSpell = null
        }
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
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
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

    /**
     * Prefix completion. Uses the 2-char index when possible; falls back
     * to 1-char for very short input.
     */
    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashSet<String>(max * 2)

        val userBucket: List<String>? = if (p.length >= 2) {
            userPrefix2[p.substring(0, 2)]
        } else {
            userPrefix1[p.substring(0, 1)]
        }
        userBucket?.asSequence()
            ?.filter { it.startsWith(p) }
            ?.take(max)
            ?.forEach { out.add(it) }

        if (out.size < max) {
            val bucket: List<String>? = if (p.length >= 2) {
                prefix2[p.substring(0, 2)]
            } else {
                prefix1[p.substring(0, 1)]
            }
            bucket?.asSequence()
                ?.filter { it.startsWith(p) }
                ?.take(max - out.size)
                ?.forEach { out.add(it) }
        }
        return out.take(max).map { match(prefix, it) }
    }

    /**
     * Correction via SymSpell. Falls back to the old prefix-bucket scan
     * if SymSpell isn't available (empty dictionary, or init failure).
     */
    fun correction(word: String): String? {
        val w = word.lowercase()

        contractions[w]?.let { return match(word, it) }
        if (w.length < 3 || !w.all { it.isLetter() || it == '\'' }) return null
        if (contains(w)) return null

        val ss = symSpell
        if (ss != null) {
            try {
                val suggestions = ss.lookup(w, 2)
                val best = suggestions.firstOrNull()?.term
                if (best != null && best != w) return match(word, best)
                return null
            } catch (_: Exception) {
                // Fall through to prefix-bucket scan.
            }
        }

        // Fallback: prefix-bucket scan for edit distance 1.
        val candidates: Iterable<String> = if (w.length >= 2) {
            prefix2[w.substring(0, 2)] ?: emptyList()
        } else {
            words.keys
        }
        var best: String? = null
        var bestFreq = 0L
        for (cand in candidates) {
            if (cand.length !in (w.length - 1)..(w.length + 1)) continue
            if (cand.firstOrNull() != w.firstOrNull()) continue
            val freq = words[cand] ?: continue
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
