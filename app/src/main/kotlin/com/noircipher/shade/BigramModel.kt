package com.noircipher.shade

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device bigram/trigram next-word predictor.
 * Stores P(next | prev) and P(next | prev2, prev1) as decayed frequency counts.
 * Pure Kotlin, no JNI, no external model file.
 */
class BigramModel(private val ctx: Context) {

    // key: "prev" -> (candidate -> decayed count)
    private val bigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    // key: "prev2|prev1" -> (candidate -> decayed count)
    private val trigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    // key: "prev" -> total observed count for normalization
    private val bigramTotals = ConcurrentHashMap<String, Float>()
    private val trigramTotals = ConcurrentHashMap<String, Float>()

    private val store = File(ctx.filesDir, "ngram.json")
    private var dirty = false

    init { load() }

    /**
     * Update counts from a newly committed word.
     * [prev2] and [prev1] are the two words before [word] in the sentence context.
     */
    @Synchronized
    fun observe(prev2: String?, prev1: String?, word: String) {
        val w = word.lowercase()
        if (w.isEmpty()) return

        if (prev1 != null) {
            val p1 = prev1.lowercase()
            val m = bigrams.getOrPut(p1) { HashMap() }
            m[w] = (m[w] ?: 0f) * DECAY + 1f
            bigramTotals[p1] = (bigramTotals[p1] ?: 0f) * DECAY + 1f
        }
        if (prev2 != null && prev1 != null) {
            val key = "${prev2.lowercase()}|${prev1.lowercase()}"
            val m = trigrams.getOrPut(key) { HashMap() }
            m[w] = (m[w] ?: 0f) * DECAY + 1f
            trigramTotals[key] = (trigramTotals[key] ?: 0f) * DECAY + 1f
        }
        dirty = true
    }

    /**
     * Score candidates for next-word prediction given [prev2] and [prev1].
     * Returns pairs sorted by descending score. Empty if no context is known.
     */
    fun predict(prev2: String?, prev1: String?, max: Int): List<Pair<String, Float>> {
        if (prev1 == null) return emptyList()
        val p1 = prev1.lowercase()
        val p2 = prev2?.lowercase()

        val scores = HashMap<String, Float>()

        // trigram: weight 0.7
        if (p2 != null) {
            val tk = "$p2|$p1"
            val tm = trigrams[tk]
            val tt = trigramTotals[tk] ?: 0f
            if (tm != null && tt > 0f) {
                for ((w, c) in tm) scores[w] = (scores[w] ?: 0f) + 0.7f * (c / tt)
            }
        }
        // bigram: weight 0.3
        val bm = bigrams[p1]
        val bt = bigramTotals[p1] ?: 0f
        if (bm != null && bt > 0f) {
            for ((w, c) in bm) scores[w] = (scores[w] ?: 0f) + 0.3f * (c / bt)
        }

        if (scores.isEmpty()) return emptyList()
        return scores.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key to it.value }
    }

    /**
     * Rerank an existing suggestion list using bigram context.
     * Returns the list in the new order, with score 0 for words not in the model.
     */
    fun rerank(prev2: String?, prev1: String?, candidates: List<String>): List<String> {
        if (candidates.isEmpty() || prev1 == null) return candidates
        val scores = predict(prev2, prev1, candidates.size * 2).toMap()
        return candidates.sortedByDescending { scores[it.lowercase()] ?: 0f }
    }

    fun save() {
        if (!dirty) return
        try {
            val root = JSONObject()
            val b = JSONObject()
            for ((k, m) in bigrams) {
                val o = JSONObject()
                for ((w, c) in m) o.put(w, c.toDouble())
                b.put(k, o)
            }
            val t = JSONObject()
            for ((k, m) in trigrams) {
                val o = JSONObject()
                for ((w, c) in m) o.put(w, c.toDouble())
                t.put(k, o)
            }
            root.put("b", b)
            root.put("t", t)
            store.writeText(root.toString())
            dirty = false
        } catch (_: Exception) {}
    }

    private fun load() {
        if (!store.exists()) return
        try {
            val root = JSONObject(store.readText())
            root.optJSONObject("b")?.let { b ->
                for (k in b.keys()) {
                    val o = b.getJSONObject(k)
                    val m = HashMap<String, Float>()
                    var total = 0f
                    for (w in o.keys()) {
                        val c = o.getDouble(w).toFloat()
                        m[w] = c
                        total += c
                    }
                    bigrams[k] = m
                    bigramTotals[k] = total
                }
            }
            root.optJSONObject("t")?.let { t ->
                for (k in t.keys()) {
                    val o = t.getJSONObject(k)
                    val m = HashMap<String, Float>()
                    var total = 0f
                    for (w in o.keys()) {
                        val c = o.getDouble(w).toFloat()
                        m[w] = c
                        total += c
                    }
                    trigrams[k] = m
                    trigramTotals[k] = total
                }
            }
        } catch (_: Exception) {}
    }

    fun clear() {
        bigrams.clear(); trigrams.clear()
        bigramTotals.clear(); trigramTotals.clear()
        store.delete()
    }

    companion object {
        private const val DECAY = 0.995f
    }
}
