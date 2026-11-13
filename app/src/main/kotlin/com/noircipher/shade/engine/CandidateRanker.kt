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

        // First-seen order wins: toMap() alone keeps the LAST index of a duplicated word,
        // which would give an early (better) source the worse fallback prior.
        val fallbackOrder = LinkedHashMap<String, Int>()
        for ((index, entry) in (neural + heuristics + dictionary).withIndex()) {
            fallbackOrder.putIfAbsent(entry.first.trim().lowercase(), index)
        }
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

    /**
     * Rank a candidate pool using neural confidence, bigram cost, and a
     * typed-prefix bonus. This is the contextual reranker used when the
     * neural predictor has live logits for the current cursor position.
     *
     * @param candidates   merged candidate pool (neural + dictionary, etc.)
     * @param neuralScores per-word neural confidence, already keyed lowercase
     * @param bigramScores per-word bigram cost (lower = better; cost, not score)
     * @param typedPrefix  what the user has typed for the current word; matches
     *                     gain a prefix bonus
     * @return ranked candidate list, descending by blended score
     */
    fun rankContextual(
        candidates: List<String>,
        neuralScores: Map<String, Float>,
        bigramScores: Map<String, Float> = emptyMap(),
        typedPrefix: String = ""
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        // Preserve original case for display, but rank on the lowercase key.
        val displayByLower = LinkedHashMap<String, String>()
        for (word in candidates) {
            val trimmed = word.trim()
            if (trimmed.isEmpty()) continue
            displayByLower.putIfAbsent(trimmed.lowercase(), trimmed)
        }
        if (displayByLower.isEmpty()) return emptyList()

        // Normalize neural scores to [0, 1] so the scale doesn't dominate.
        val maxNeural = neuralScores.values.filter { it.isFinite() }.maxOrNull() ?: 0f
        val neuralNorm: Map<String, Float> = if (maxNeural <= 0f) emptyMap()
            else neuralScores.mapValues { (_, v) ->
                (v / maxNeural).coerceIn(0f, 1f)
            }

        // Bigram values are costs (lower = better). Convert to a [0, 1] score
        // where 1 = best (lowest cost), 0 = worst (highest cost). Skip entries
        // that don't have a finite cost — they shouldn't penalize the candidate.
        val finiteBigrams = bigramScores.filter { it.value.isFinite() && it.value >= 0f }
        val minCost = finiteBigrams.values.minOrNull() ?: 0f
        val maxCost = finiteBigrams.values.maxOrNull() ?: 0f
        val bigramNorm: Map<String, Float> = if (maxCost <= minCost) {
            // All costs equal (or empty) — give everyone the same neutral 0.5.
            finiteBigrams.mapValues { 0.5f }
        } else {
            finiteBigrams.mapValues { (_, cost) ->
                1f - (cost - minCost) / (maxCost - minCost)
            }
        }

        val prefix = typedPrefix.trim().lowercase()
        val order = displayByLower.keys.toList()
        val denominator = order.size.coerceAtLeast(1).toFloat()

        return order
            .mapIndexed { index, key ->
                val word = displayByLower[key] ?: key
                val neural = neuralNorm[key] ?: 0f
                val bigram = bigramNorm[key] ?: 0f
                // Prior: position-based fallback so we never produce ties
                // when scores are 0 across the board.
                val prior = 1f - index.toFloat() / denominator
                // Prefix bonus: only when the user has typed something.
                val prefixBonus = if (prefix.isEmpty()) 0f
                    else if (word.equals(prefix, ignoreCase = true)) 0.5f
                    else if (word.startsWith(prefix, ignoreCase = true)) 0.25f
                    else 0f
                // Blend: neural is the dominant signal (60%), bigram next (20%),
                // prefix match (10%), position prior (10%).
                val score = 0.60f * neural +
                    0.20f * bigram +
                    0.10f * prefixBonus +
                    0.10f * prior
                word to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
