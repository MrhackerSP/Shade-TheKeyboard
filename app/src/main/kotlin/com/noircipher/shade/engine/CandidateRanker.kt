package com.noircipher.shade.engine

object CandidateRanker {
    fun mergeNextWord(
        neural: List<String>,
        dictionary: List<String>,
        limit: Int = 3
    ): List<String> {
        val merged = LinkedHashMap<String, String>()
        for (candidate in neural + dictionary) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (!merged.containsKey(key)) merged[key] = trimmed
        }
        return merged.values.take(limit)
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
