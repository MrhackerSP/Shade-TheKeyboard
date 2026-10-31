package com.noircipher.shade

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * On-device bigram/trigram next-word predictor with decayed counts.
 *
 * On first run, a bundled `prime.json` seeds the model with ~200 common
 * bigrams so the first sentence typed gets real predictions instead of
 * an empty list.
 */
class BigramModel(private val ctx: Context) {

    private val keyAlias = "shade_bigram_key"

    private val bigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    private val trigrams = ConcurrentHashMap<String, HashMap<String, Float>>()
    private val bigramTotals = ConcurrentHashMap<String, Float>()
    private val trigramTotals = ConcurrentHashMap<String, Float>()

    private val store = File(ctx.filesDir, "ngram.json")
    private var dirty = false

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    } catch (_: Exception) { null }

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

    @Synchronized
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

    @Synchronized
    fun rerank(prev2: String?, prev1: String?, candidates: List<String>): List<String> {
        if (candidates.isEmpty() || prev1 == null) return candidates
        val scores = predict(prev2, prev1, candidates.size * 2).toMap()
        return candidates.sortedByDescending { scores[it.lowercase()] ?: 0f }
    }

    @Synchronized
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
            val json = root.toString()
            val encrypted = encrypt(json)
            val tmp = store.resolveSibling(store.name + ".tmp")
            if (encrypted != null) {
                tmp.writeText(encrypted)
            } else {
                // Fall back to plaintext if encryption fails (e.g., key not available)
                Log.w("BigramModel", "Encryption failed; falling back to plaintext")
                tmp.writeText(json)
            }
            if (!tmp.renameTo(store)) {
                store.writeText(if (encrypted != null) encrypted else json)
            }
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
            val raw = store.readText() ?: return
            val json = decrypt(raw) ?: raw  // fall back to plaintext if decrypt fails
            val root = JSONObject(json)
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
        } catch (e: Exception) {
            Log.w("BigramModel", "Failed to load bigrams; starting fresh", e)
            // Keep the unreadable file (e.g. Keystore key lost) instead of letting the
            // next save() overwrite it silently.
            try { store.renameTo(java.io.File(store.parentFile, store.name + ".bad")) } catch (_: Exception) {}
        }
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
