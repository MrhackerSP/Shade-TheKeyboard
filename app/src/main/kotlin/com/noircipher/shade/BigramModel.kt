package com.noircipher.shade

import android.content.Context
import android.util.Log
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

    private val stats = NgramStats(DECAY)
    private val bigrams = stats.bigrams
    private val trigrams = stats.trigrams
    private val bigramTotals = stats.bigramTotals
    private val trigramTotals = stats.trigramTotals

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
        if (stats.observe(prev2, prev1, word)) dirty = true
    }

    @Synchronized
    fun predict(prev2: String?, prev1: String?, max: Int): List<Pair<String, Float>> =
        stats.predict(prev2, prev1, max, staticNext)

    @Synchronized
    fun rerank(prev2: String?, prev1: String?, candidates: List<String>): List<String> =
        stats.rerank(prev2, prev1, candidates, staticNext)

    @Synchronized
    fun save() {
        if (!dirty) return
        try {
            // Fail closed: if the Keystore can't encrypt, keep the data in memory only.
            val encrypted = SecureStore.encrypt(KEY_ALIAS, toJson())
            if (encrypted == null) {
                Log.w("BigramModel", "Encryption unavailable; n-grams not persisted")
                return
            }
            val tmp = store.resolveSibling(store.name + ".tmp")
            tmp.writeText(encrypted)
            if (!tmp.renameTo(store)) {
                store.writeText(encrypted)
                tmp.delete()
            }
            dirty = false
        } catch (_: Exception) {}
    }

    private fun toJson(): String {
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
        return root.toString()
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
            val raw = store.readText()
            // A file starting with '{' is legacy plaintext from older builds; anything
            // else must decrypt. Legacy data is re-saved encrypted on the next save().
            val legacyPlain = raw.trimStart().startsWith("{")
            val json = if (legacyPlain) raw else SecureStore.decrypt(KEY_ALIAS, raw)
                ?: throw IllegalStateException("n-gram file cannot be decrypted")
            val root = JSONObject(json)
            loadTable(root.optJSONObject("b"), bigrams, bigramTotals)
            loadTable(root.optJSONObject("t"), trigrams, trigramTotals)
            if (legacyPlain) dirty = true
        } catch (e: Exception) {
            Log.w("BigramModel", "Failed to load bigrams; starting fresh", e)
            // Keep the unreadable file (e.g. Keystore key lost) instead of letting the
            // next save() overwrite it silently.
            try { store.renameTo(java.io.File(store.parentFile, store.name + ".bad")) } catch (_: Exception) {}
        }
    }

    private fun loadTable(
        src: JSONObject?,
        table: ConcurrentHashMap<String, HashMap<String, Float>>,
        totals: ConcurrentHashMap<String, Float>
    ) {
        if (src == null) return
        for (k in src.keys()) {
            val o = src.getJSONObject(k)
            val m = HashMap<String, Float>()
            var total = 0f
            for (w in o.keys()) {
                val c = o.getDouble(w).toFloat()
                m[w] = c
                total += c
            }
            table[k] = m
            totals[k] = total
        }
    }

    @Synchronized
    fun clear() {
        stats.clear()
        // Hold the same lock as save() and drop the dirty flag: otherwise a debounced save
        // running concurrently (or shutdown's flush thread) re-writes the just-deleted file
        // and the "cleared" data resurrects on the next launch.
        dirty = false
        store.delete()
        try { store.resolveSibling(store.name + ".tmp").delete() } catch (_: Exception) {}
    }

    companion object {
        private const val DECAY = 0.995f
        private const val KEY_ALIAS = "shade_bigram_key"

        /** Decrypted n-gram JSON as currently persisted on disk, or null if absent/unreadable. */
        fun readPersistedJson(ctx: Context): String? = try {
            val f = File(ctx.filesDir, "ngram.json")
            if (!f.exists()) null else {
                val raw = f.readText()
                if (raw.trimStart().startsWith("{")) raw else SecureStore.decrypt(KEY_ALIAS, raw)
            }
        } catch (_: Exception) { null }
    }
}
