package com.noircipher.shade.engine

object CandidateRanker {
    fun mergeNextWord(
        neural: List<String>,
        dictionary: List<String>,
        limit: Int = 3
    ): List<String> {
        return (neural + dictionary).distinct().take(limit)
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
