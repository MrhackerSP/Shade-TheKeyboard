package com.noircipher.shade.engine

/**
 * De-duplicates a suggestion list by stem, so the user doesn't see "type",
 * "types", "typed" all in the same suggestion bar.
 *
 * The stemmer is a very light Porter-style prefix trimmer — it is not a full
 * morphological analyser, just enough to cluster obviously-related forms
 * together.  When two suggestions share the same stem, the higher-scored one
 * wins and the others are dropped.
 *
 * Returns the deduplicated list, preserving relative order of the kept items.
 */
object MorphologicalDiversity {

    private val SUFFIXES = listOf(
        "ing", "edly", "ed", "ies", "ied", "ier", "iest", "ly", "es", "s",
        "ment", "ness", "tion", "sion", "able", "ible", "ous", "ful", "less",
        "ize", "ise", "ify", "ity", "ive", "al", "er", "or", "est"
    )

    /** A very light stemmer: trims common English suffixes. */
    fun stem(word: String): String {
        if (word.length < 4) return word.lowercase()
        val lower = word.lowercase()
        for (suffix in SUFFIXES) {
            if (lower.length > suffix.length + 2 && lower.endsWith(suffix)) {
                return lower.substring(0, lower.length - suffix.length)
            }
        }
        return lower
    }

    /**
     * Deduplicate [candidates] (word, score) by stem, keeping the highest-
     * scored candidate per stem and preserving relative order.
     */
    fun deduplicate(candidates: List<Pair<String, Float>>): List<Pair<String, Float>> {
        if (candidates.size <= 1) return candidates
        val bestByStem = LinkedHashMap<String, Pair<String, Float>>()
        for ((word, score) in candidates) {
            val stem = stem(word)
            val existing = bestByStem[stem]
            if (existing == null || score > existing.second) {
                bestByStem[stem] = word to score
            }
        }
        return bestByStem.values.toList()
    }

    /**
     * Convenience overload for plain string lists — deduplicates by stem,
     * preserving input order of first occurrence.
     */
    fun deduplicate(candidates: List<String>): List<String> {
        if (candidates.size <= 1) return candidates
        val seen = HashSet<String>()
        val out = ArrayList<String>(candidates.size)
        for (word in candidates) {
            val stem = stem(word)
            if (stem in seen) continue
            seen.add(stem)
            out.add(word)
        }
        return out
    }
}
