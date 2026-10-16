package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import com.darkrockstudios.symspellkt.api.SpellChecker
import com.darkrockstudios.symspellkt.api.loadUnigramTxtFile
import com.darkrockstudios.symspellkt.common.DamerauLevenshteinDistance
import com.darkrockstudios.symspellkt.common.Murmur3HashFunction
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.SuggestItem
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.InMemoryDictionaryHolder
import com.darkrockstudios.symspellkt.impl.SymSpell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline dictionaries backed by SymSpellKt (Symmetric Delete algorithm).
 * Dictionary is loaded directly from Norvig's plain-text frequency list
 * (count_1w.txt) bundled in assets.
 */
class WordDictionary(private val ctx: Context) {

    private var spellChecker: SpellChecker? = null
    private var loadedId: String? = null

    private val prefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Float> = HashMap()

    var bigramModel: BigramModel? = null

    init { reloadUser() }

    /**
     * Load Norvig's plain-text frequency dictionary into SymSpellKt.
     * Called on a background dispatcher because loading is I/O-bound.
     *
     * Norvig's count_1w.txt format: "word frequency" per line (UTF-8).
     * SymSpellKt's loadUnigramTxtFile expects exactly this format.
     */
    suspend fun configure(localeId: String = "en") {
        if (loadedId == localeId && spellChecker != null) return
        loadedId = localeId

        val settings = SpellCheckSettings(
            maxEditDistance = 2.0,
            prefixLength = 7,
            countThreshold = 1
        )

        val holder = InMemoryDictionaryHolder(settings, Murmur3HashFunction())

        try {
            val text = ctx.assets.open("wordlists/${localeId}.txt")
                .bufferedReader()
                .use { it.readText() }
            holder.loadUnigramTxtFile(text)
        } catch (_: Exception) {
            // Dictionary failed to load; spell checker will be unavailable.
        }

        spellChecker = SymSpell(
            settings,
            DamerauLevenshteinDistance(),
            holder
        )
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
        if (user.containsKey(w)) return true
        val sc = spellChecker ?: return false
        val suggestions = sc.lookup(w, Verbosity.Top, 0)
        return suggestions.isNotEmpty() && suggestions[0].distance == 0
    }

    /**
     * Learn a word with temporal decay and feed the bigram model.
     */
    fun learn(word: String, prev2: String? = null, prev1: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        val old = user[w] ?: 0f
        val score = old * DECAY + 1f
        user[w] = score
        prefs.edit().putFloat(w, score).apply()
        bigramModel?.observe(prev2, prev1, w)
    }

    /**
     * Prefix completion. User-learned words are scored first, then SymSpell
     * suggestions filtered by prefix.
     */
    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashSet<String>(max * 2)

        // 1. user-learned words matching the prefix, ranked by decayed score
        user.entries.asSequence()
            .filter { it.key.startsWith(p) }
            .sortedByDescending { it.value }
            .take(max)
            .forEach { out.add(it.key) }

        // 2. SymSpell lookup with no edit distance (pure prefix / exact match)
        val sc = spellChecker
        if (sc != null && out.size < max) {
            val suggestions = sc.lookup(p, Verbosity.All, 0)
            for (s in suggestions) {
                if (out.size >= max) break
                if (s.item.startsWith(p)) out.add(s.item)
            }
        }

        return out.take(max).map { match(prefix, it) }
    }

    /**
     * Autocorrect a single word. SymSpellKt returns suggestions ordered by
     * edit distance, then term frequency.
     */
    fun correction(word: String): String? {
        val w = word.lowercase()
        if (w.length < 3 || !w.all { it.isLetter() || it == '\'' }) return null

        val sc = spellChecker ?: return null

        val suggestions = sc.lookup(w, Verbosity.Closest, 1)
        if (suggestions.isEmpty()) return null

        val best: SuggestItem = suggestions[0]
        if (best.distance == 0) return null

        return match(word, best.item)
    }

    /**
     * Compound correction: splits wrongly merged words and merges wrongly
     * split ones using the bigram dictionary for context.
     */
    fun correctionCompound(input: String): String? {
        val sc = spellChecker ?: return null
        return try {
            val result = sc.lookupCompound(input, 2)
            result.joinToString(" ").takeIf { it.isNotEmpty() && it != input }
        } catch (_: Exception) {
            null
        }
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
