package com.noircipher.shade

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device bigram/trigram next-word predictor with decayed counts.
 *
 * On first run, a bundled `prime.json` seeds the model with ~200 common
 * bigrams so the first sentence typed gets real predictions instead of
 * an empty list.
 */
class BigramModel(private val ctx: Context) {

    private val bigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    private val trigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    private val bigramTotals = ConcurrentHashMap<String, Float>()
    private val trigramTotals = ConcurrentHashMap<String, Float>()

    private val store = File(ctx.filesDir, "ngram.json")
    private var dirty = false

    /** Read-only next-word table bundled with the app: word -> best next words, best first. */
    @Volatile private var staticNext: Map<String, Array<String>> = emptyMap()

    init {
        load()
        if (bigrams.isEmpty()) seedFromAssets()
        Thread { loadStatic() }.apply { isDaemon = true; start() }
    }

    private fun loadStatic() {
        try {
            val m = HashMap<String, Array<String>>(40000)
            ctx.assets.open("bigrams_en.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size >= 2) m[p[0]] = p.subList(1, p.size).toTypedArray()
                }
            }
            staticNext = m
        } catch (_: Exception) {
            // No bundled table: only learned data is used.
        }
    }

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

    fun predict(prev2: String?, prev1: String?, max: Int): List<Pair<String, Float>> {
        if (prev1 == null) return emptyList()
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
                scores[w] = (scores[w] ?: 0f) + 0.18f - 0.06f * i
            }
        }

        if (scores.isEmpty()) return emptyList()
        return scores.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key to it.value }
    }

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

    /**
     * Seed the model from bundled `prime.json`. Format is a flat object
     * mapping `prev` to `{next: weight}`. Called only when the persisted
     * ngram file is empty, so it runs once per install.
     */
    private fun seedFromAssets() {
        try {
            val text = ctx.assets.open("prime.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            for (key in root.keys()) {
                val inner = root.getJSONObject(key)
                val m = HashMap<String, Float>()
                var total = 0f
                for (w in inner.keys()) {
                    val c = inner.getDouble(w).toFloat()
                    m[w] = c
                    total += c
                }
                bigrams[key] = m
                bigramTotals[key] = total
            }
        } catch (_: Exception) {
            // No prime file — start cold, same as before.
        }
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
