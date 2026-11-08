package com.noircipher.shade.engine

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
}
