package com.noircipher.shade

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure (no Android types) decayed bigram/trigram counts and scoring.
 * [BigramModel] owns persistence; this class owns the maths so it can be unit-tested.
 *
 * Invariant: for every context, the stored total equals the sum of its word counts.
 * Every observation decays *all* counts of that context, so count/total stays in [0, 1].
 */
class NgramStats(private val decay: Float = DEFAULT_DECAY) {

    val bigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    val trigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    val bigramTotals = ConcurrentHashMap<String, Float>()
    val trigramTotals = ConcurrentHashMap<String, Float>()

    /** @return true if the observation changed the model. */
    @Synchronized
    fun observe(prev2: String?, prev1: String?, word: String): Boolean {
        val w = word.lowercase()
        if (w.isEmpty()) return false
        if (prev1 != null) {
            val p1 = prev1.lowercase()
            bump(bigrams.getOrPut(p1) { HashMap() }, w)
            bigramTotals[p1] = (bigramTotals[p1] ?: 0f) * decay + 1f
        }
        if (prev2 != null && prev1 != null) {
            val key = "${prev2.lowercase()}|${prev1.lowercase()}"
            bump(trigrams.getOrPut(key) { HashMap() }, w)
            trigramTotals[key] = (trigramTotals[key] ?: 0f) * decay + 1f
        }
        return true
    }

    private fun bump(m: HashMap<String, Float>, w: String) {
        for (e in m.entries) e.setValue(e.value * decay)
        m[w] = (m[w] ?: 0f) + 1f
    }

    /** Best next words with their blended score, best first. */
    @Synchronized
    fun predict(
        prev2: String?,
        prev1: String?,
        max: Int,
        staticNext: Map<String, Array<String>> = emptyMap()
    ): List<Pair<String, Float>> {
        if (prev1 == null || max <= 0) return emptyList()
        val p1 = prev1.lowercase()
        val p2 = prev2?.lowercase()
        val scores = HashMap<String, Float>()

        if (p2 != null) {
            val tk = "$p2|$p1"
            val tm = trigrams[tk]
            val tt = trigramTotals[tk] ?: 0f
            if (tm != null && tt > 0f) {
                for ((w, c) in tm) scores[w] = (scores[w] ?: 0f) + 0.7f * (c / tt)
            }
        }
        val bm = bigrams[p1]
        val bt = bigramTotals[p1] ?: 0f
        if (bm != null && bt > 0f) {
            for ((w, c) in bm) scores[w] = (scores[w] ?: 0f) + 0.3f * (c / bt)
        }
        staticNext[p1]?.let { arr ->
            for ((i, w) in arr.withIndex()) {
                // Never subtract: a long static list must not push words below zero.
                scores[w] = (scores[w] ?: 0f) + (0.18f - 0.06f * i).coerceAtLeast(0.01f)
            }
        }
        if (scores.isEmpty()) return emptyList()
        return scores.entries.sortedByDescending { it.value }.take(max).map { it.key to it.value }
    }

    @Synchronized
    fun rerank(
        prev2: String?,
        prev1: String?,
        candidates: List<String>,
        staticNext: Map<String, Array<String>> = emptyMap()
    ): List<String> {
        if (candidates.isEmpty() || prev1 == null) return candidates
        val scores = predict(prev2, prev1, candidates.size * 2, staticNext).toMap()
        return candidates.sortedByDescending { scores[it.lowercase()] ?: 0f }
    }

    @Synchronized
    fun clear() {
        bigrams.clear(); trigrams.clear()
        bigramTotals.clear(); trigramTotals.clear()
    }

    companion object {
        const val DEFAULT_DECAY = 0.995f
    }
}
