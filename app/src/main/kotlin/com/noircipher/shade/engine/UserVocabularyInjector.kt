package com.noircipher.shade.engine

/**
 * Injects user-learned vocabulary into a list of neural model predictions.
 *
 * The neural model has no knowledge of words the user has personally added
 * (e.g. names, technical jargon).  This helper takes the model's top-K
 * predictions and merges in user words that share a prefix with the typed
 * text, boosting them when the neural model ranked them low or omitted them.
 *
 * The merge is stable: existing entries are kept in their relative order,
 * new user-word entries are inserted at a position determined by their
 * user-score (higher score → earlier position, but never displacing the
 * neural top-1 unless the user score is very high).
 */
object UserVocabularyInjector {

    /**
     * Merge [userWords] (a map of word → user score in [0,1]) into the
     * [neuralPredictions] list, returning a new list of size at most
     * [limit].
     *
     * Only user words that start with [prefix] are considered.  When a user
     * word is already present in the neural list, its score is boosted rather
     * than duplicated.
     */
    fun inject(
        neuralPredictions: List<Pair<String, Float>>,
        userWords: Map<String, Float>,
        prefix: String,
        limit: Int
    ): List<Pair<String, Float>> {
        if (limit <= 0) return emptyList()
        val p = prefix.lowercase()
        val out = ArrayList(neuralPredictions.take(limit).map { it.first to it.second })

        if (p.isNotEmpty()) {
            val matchingUser = userWords.entries
                .filter { it.key.startsWith(p) }
                .sortedByDescending { it.value }
            for ((word, userScore) in matchingUser) {
                if (out.size >= limit) break
                val existing = out.indexOfFirst { it.first.equals(word, ignoreCase = true) }
                if (existing >= 0) {
                    val boosted = out[existing].second + 0.4f * userScore
                    out[existing] = word to boosted.coerceAtMost(1.0f)
                } else if (userScore > 0.15f) {
                    // Insert near the top — but never above the neural top-1
                    // unless the user score is dominant.
                    val insertAt = if (userScore > 0.6f) 1 else minOf(out.size, 2 + (1f - userScore).times(out.size).toInt())
                    out.add(insertAt.coerceIn(1, out.size), word to userScore)
                }
            }
        }

        return out.take(limit)
    }
}
