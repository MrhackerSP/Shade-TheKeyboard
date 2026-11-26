package com.shade.keyboard.engine

/**
 * Last step of the typed-word suggestion pipeline, kept free of Android/service state
 * so it can be unit-tested: decides what the three suggestion slots finally show.
 */
object SuggestionFinalizer {

    /** Puts the best candidate in the middle slot: [1], [0], [2]. */
    fun centerBest(l: List<String>): List<String> = when (l.size) {
        0, 1 -> l
        2 -> listOf(l[1], l[0])
        else -> listOf(l[1], l[0], l[2])
    }

    /**
     * @param prefix            the word being typed (non-empty)
     * @param known             whether the dictionary contains [prefix]
     * @param grammarTop        grammar engine's top correction, if any
     * @param ranked            ranked candidates
     * @param phraseSuggestionsOn false = word-by-word mode: multi-word entries are dropped
     */
    fun finalize(
        prefix: String,
        known: Boolean,
        grammarTop: String?,
        ranked: List<String>,
        phraseSuggestionsOn: Boolean
    ): List<String> {
        var shown: List<String> = when {
            grammarTop != null && !grammarTop.equals(prefix, ignoreCase = true) && !known ->
                (listOf(grammarTop) + ranked + listOf(prefix)).distinct().take(3)

            !known && ranked.isNotEmpty() ->
                // Typed word first (so it can be kept as-is), never repeated further on.
                (listOf(prefix) + ranked.filter { !it.equals(prefix, ignoreCase = true) }).take(3)

            else -> centerBest(ranked.ifEmpty { listOf(prefix) }.take(3))
        }
        // Word-by-word mode: strip multi-word candidates, but never leave the strip empty.
        if (!phraseSuggestionsOn && shown.isNotEmpty()) {
            val singleWord = shown.filter { !it.contains(' ') }
            shown = if (singleWord.isEmpty()) shown else singleWord
        }
        return shown
    }

    /**
     * §2 — one final scored ranking pass: injection sources compete with the
     * ML-ranked list by weight × within-source rank instead of hard
     * prepending, which used to let ~15 bypass rounds unconditionally override
     * the ML ranking (a time-aware word jumped to position 0 regardless of
     * context fit). The ML list keeps a full-weight positional prior
     * (1/(i+1)), so an injection only displaces ML candidates when its source
     * prior and rank actually beat them; a word present in both keeps its
     * best score; ties keep first-seen (ML) order. Weights are hand priors —
     * to be auto-tuned offline from acceptance attribution later.
     *
     * @param ranked     ML-ordered candidates (best first)
     * @param injections pairs of (source prior weight, candidates best first)
     */
    fun mergeScored(
        ranked: List<String>,
        injections: List<Pair<Float, List<String>>>
    ): List<String> = mergeScoredWithScores(ranked, injections).keys.toList()

    /**
     * R4 — score-carrying variant of [mergeScored] for the bandit swap gate:
     * keys are the first-seen display casing, values the final merge score,
     * iteration order is score-descending.
     */
    fun mergeScoredWithScores(
        ranked: List<String>,
        injections: List<Pair<Float, List<String>>>
    ): LinkedHashMap<String, Float> {
        // R6 — key by lowercase so "the"/"The"/"THE" share one slot; keep the
        // first-seen display casing. Best score wins; ties keep first-seen
        // order (LinkedHashMap + stable sort).
        val scores = LinkedHashMap<String, Float>()
        val display = LinkedHashMap<String, String>()
        fun offer(word: String, score: Float) {
            if (word.isEmpty()) return
            val key = word.lowercase()
            display.putIfAbsent(key, word)
            val existing = scores[key]
            if (existing == null || score > existing) scores[key] = score
        }
        ranked.forEachIndexed { index, word -> offer(word, 1f / (index + 1)) }
        for ((weight, words) in injections) {
            if (weight <= 0f) continue
            words.forEachIndexed { index, word -> offer(word, weight / (index + 1)) }
        }
        val result = LinkedHashMap<String, Float>()
        scores.entries.sortedByDescending { it.value }.forEach { (key, score) ->
            result[display[key] ?: key] = score
        }
        return result
    }
}
