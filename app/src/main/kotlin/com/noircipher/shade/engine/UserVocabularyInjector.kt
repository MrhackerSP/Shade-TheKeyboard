package com.noircipher.shade.engine

/**
 * #6 — User vocabulary injector.
 *
 * Merges the user's learned words (with their scores) into the neural
 * engine's predictions. User words that match the typed prefix get a score
 * boost proportional to how often the user types them.
 *
 * The result is sorted by score descending and capped to `limit`.
 */
object UserVocabularyInjector {

    fun inject(
        neuralPredictions: List<Pair<String, Float>>,
        userWords: Map<String, Float>,
        prefix: String,
        limit: Int
    ): List<Pair<String, Float>> {
        if (limit <= 0) return emptyList()
        val merged = LinkedHashMap<String, Float>()

        // Seed with neural predictions.
        for ((word, score) in neuralPredictions) {
            if (word.isEmpty()) continue
            merged[word.lowercase()] = score
        }

        // Boost existing entries and inject new ones for user words matching the prefix.
        for ((word, userScore) in userWords) {
            if (word.isEmpty()) continue
            val w = word.lowercase()
            if (prefix.isNotEmpty() && !w.startsWith(prefix.lowercase(), ignoreCase = true)) continue
            // User-score is in [0, 1]; map it to the same range as neural scores.
            val boost = userScore.coerceIn(0f, 1f) * 0.7f
            val existing = merged[w] ?: 0f
            merged[w] = existing + boost
        }

        return merged.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }
}
