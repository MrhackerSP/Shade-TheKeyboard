package com.noircipher.shade.engine

/**
 * #5 — Morphological diversity.
 *
 * When several candidates share the same root (e.g. "type", "types", "typed",
 * "typing"), keep only the highest-ranked one. This prevents the suggestion
 * bar from being dominated by inflectional variants of a single word.
 */
object MorphologicalDiversity {

    private val suffixes = listOf(
        "ing", "ed", "es", "s", "er", "est", "ers", "est",
        "ly", "ment", "ness", "tion", "sion", "able", "ible",
        "al", "ity", "ous", "ive", "less", "ful", "ize", "ise"
    )

    /**
     * Heuristic stemmer: strips recognized English suffixes.
     */
    private fun stem(word: String): String {
        val w = word.lowercase()
        for (s in suffixes.sortedByDescending { it.length }) {
            if (w.length > s.length + 2 && w.endsWith(s)) {
                return w.substring(0, w.length - s.length)
            }
        }
        return w
    }

    fun deduplicate(candidates: List<String>, limit: Int): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val seenStems = LinkedHashSet<String>()
        val out = ArrayList<String>(candidates.size)
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val stem = stem(candidate)
            // Only collapse when stems are at least 4 chars — short words like
            // "the/they/their" would otherwise get squashed.
            if (stem.length >= 4) {
                if (!seenStems.add(stem)) continue
            } else {
                if (!seenStems.add(candidate.lowercase())) continue
            }
            out.add(candidate)
            if (out.size >= limit) break
        }
        return out
    }
}
