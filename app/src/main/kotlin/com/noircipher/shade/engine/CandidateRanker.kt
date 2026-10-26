package com.noircipher.shade.engine

object CandidateRanker {
    fun mergeNextWord(
        neural: List<String>,
        dictionary: List<String>,
        limit: Int = 3
    ): List<String> {
        // Interleave (neural first) so learned/bigram words get a slot even when
        // the neural engine returns a full list.
        val out = LinkedHashMap<String, String>()
        val a = neural.iterator()
        val b = dictionary.iterator()
        while (out.size < limit && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) a.next().let { out.putIfAbsent(it.lowercase(), it) }
            if (out.size < limit && b.hasNext()) b.next().let { out.putIfAbsent(it.lowercase(), it) }
        }
        return out.values.take(limit)
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
