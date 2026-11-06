package com.noircipher.shade.engine

object CandidateRanker {
    /**
     * Merge next-word candidates while retaining their source confidence.
     * Scores are normalized within each source before blending, since neural,
     * dictionary and heuristic scores have different scales.
     */
    fun mergeNextWordScored(
        neural: List<Pair<String, Float>>,
        heuristics: List<Pair<String, Float>> = emptyList(),
        dictionary: List<Pair<String, Float>> = emptyList(),
        limit: Int = 3
    ): List<String> {
        if (limit <= 0) return emptyList()

        fun normalized(values: List<Pair<String, Float>>): Map<String, Float> {
            val finite = values.filter { it.first.isNotBlank() && it.second.isFinite() }
            val maxScore = finite.maxOfOrNull { it.second } ?: return emptyMap()
            if (maxScore <= 0f) return emptyMap()
            return finite.groupBy { it.first.trim().lowercase() }
                .mapValues { (_, entries) -> (entries.maxOf { it.second } / maxScore).coerceIn(0f, 1f) }
        }

        val neuralScores = normalized(neural)
        val heuristicScores = normalized(heuristics)
        val dictionaryScores = normalized(dictionary)
        val displayWords = LinkedHashMap<String, String>()
        for ((word, _) in neural + heuristics + dictionary) {
            val trimmed = word.trim()
            if (trimmed.isNotEmpty()) displayWords.putIfAbsent(trimmed.lowercase(), trimmed)
        }
        if (displayWords.isEmpty()) return emptyList()

        val fallbackOrder = (neural.map { it.first } + heuristics.map { it.first } + dictionary.map { it.first })
            .mapIndexed { index, word -> word.trim().lowercase() to index }
            .toMap()
        val denominator = displayWords.size.coerceAtLeast(1).toFloat()

        return displayWords.entries
            .mapIndexed { index, (key, word) ->
                // Neural confidence drives the ranking. User/context dictionary
                // evidence and heuristics contribute without winning by list order.
                val neural = neuralScores[key] ?: 0f
                val heuristic = heuristicScores[key] ?: 0f
                val dictionary = dictionaryScores[key] ?: 0f
                val prior = 1f - (fallbackOrder[key] ?: index).toFloat() / denominator
                val score = 0.65f * neural + 0.20f * dictionary + 0.10f * heuristic + 0.05f * prior
                word to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Compatibility helper for callers that only have unscored candidate lists. */
    fun mergeNextWord(
        neural: List<String>,
        dictionary: List<String>,
        limit: Int = 3
    ): List<String> {
        val neuralScored = neural.mapIndexed { index, word -> word to (1f / (index + 1)) }
        val dictionaryScored = dictionary.mapIndexed { index, word -> word to (1f / (index + 1)) }
        return mergeNextWordScored(neuralScored, dictionary = dictionaryScored, limit = limit)
    }

    fun withEmojiFallback(
        ranked: List<String>,
        emojis: List<String>,
        limit: Int = 3
    ): List<String> {
        val merged = ranked.toMutableList()
        for (emoji in emojis) {
            if (merged.size >= limit) break
            if (emoji !in merged) merged.add(emoji)
        }
        return merged.take(limit)
    }
}
