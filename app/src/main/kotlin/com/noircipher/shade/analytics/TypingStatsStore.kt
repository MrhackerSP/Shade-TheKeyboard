package com.noircipher.shade.analytics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TypingStatsStore(context: Context) {
    data class Snapshot(
        val totalChars: Long,
        val activeMinutes: Long,
        val averageWpm: Float,
        val peakWpm: Int,
        val backspaces: Long,
        val suggestionsShown: Long,
        val suggestionsAccepted: Long,
        val autocorrectReverts: Long,
        val topWords: List<Pair<String, Int>>,
        val languageCounts: Map<String, Int>
    )

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("shade_typing_stats", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counters = HashMap<String, Long>()
    private val words = HashMap<String, Int>()
    private val keyAlias = "shade_typing_stats_key"
    private var lastTypingAt = 0L
    private var windowStart = 0L
    private var windowChars = 0
    private var flushJob: kotlinx.coroutines.Job? = null
    private var dirty = false

    init {
        for ((key, value) in prefs.all) {
            if (value is Long) counters[key] = value
            if (value is Int && key.startsWith("peak_")) counters[key] = value.toLong()
        }
        loadWords()
        migratePlainWords()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(text: String): String? {
        return try {
            val bytes = Base64.decode(text, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadWords() {
        val stored = prefs.getString("words_enc", null) ?: return
        val plain = decrypt(stored) ?: return
        try {
            val json = JSONObject(plain)
            for (key in json.keys()) words[key] = json.optInt(key, 0)
        } catch (_: Exception) {}
    }

    private fun migratePlainWords() {
        val old = prefs.getString("words", null) ?: return
        try {
            val json = JSONObject(old)
            for (key in json.keys()) words[key] = maxOf(words[key] ?: 0, json.optInt(key, 0))
            prefs.edit().remove("words").apply()
            dirty = true
            scheduleFlush()
        } catch (_: Exception) {
            prefs.edit().remove("words").apply()
        }
    }

    @Synchronized
    fun recordCharacters(count: Int, language: String) {
        if (count <= 0) return
        val now = System.currentTimeMillis()
        if (lastTypingAt > 0 && now - lastTypingAt < 5000L) {
            counters["active_ms"] = (counters["active_ms"] ?: 0L) + (now - lastTypingAt)
        }
        lastTypingAt = now
        if (windowStart == 0L || now - windowStart > 60_000L) {
            windowStart = now
            windowChars = 0
        }
        windowChars += count
        counters["chars"] = (counters["chars"] ?: 0L) + count
        counters["peak_wpm"] = maxOf(counters["peak_wpm"] ?: 0L, (windowChars / 5).toLong())
        counters["lang_$language"] = (counters["lang_$language"] ?: 0L) + 1
        dirty = true
        scheduleFlush()
    }

    @Synchronized
    fun recordWord(word: String) {
        val key = word.trim().lowercase()
        if (key.length !in 2..24) return
        words[key] = (words[key] ?: 0) + 1
        if (words.size > 500) {
            val smallest = words.entries.sortedBy { it.value }.take(words.size - 500)
            smallest.forEach { words.remove(it.key) }
        }
        dirty = true
        scheduleFlush()
    }

    @Synchronized
    fun recordBackspace() = bump("backspaces")

    @Synchronized
    fun recordSuggestionShown() = bump("suggestions_shown")

    @Synchronized
    fun recordSuggestionAccepted() = bump("suggestions_accepted")

    @Synchronized
    fun recordAutocorrectRevert() = bump("autocorrect_reverts")

    private fun bump(key: String) {
        counters[key] = (counters[key] ?: 0L) + 1L
        dirty = true
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(1200)
            flush()
        }
    }

    @Synchronized
    private fun flush() {
        if (!dirty) return
        val edit = prefs.edit()
        for ((key, value) in counters) {
            if (key == "peak_wpm") edit.putInt(key, value.toInt()) else edit.putLong(key, value)
        }
        val wordJson = JSONObject()
        for ((key, value) in words) wordJson.put(key, value)
        encrypt(wordJson.toString())?.let { edit.putString("words_enc", it) }
        edit.apply()
        dirty = false
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val chars = counters["chars"] ?: 0L
        val activeMs = counters["active_ms"] ?: 0L
        val minutes = (activeMs / 60000L).coerceAtLeast(1L)
        return Snapshot(
            totalChars = chars,
            activeMinutes = activeMs / 60000L,
            averageWpm = ((chars / 5f) / minutes).coerceIn(0f, 300f),
            peakWpm = (counters["peak_wpm"] ?: 0L).toInt(),
            backspaces = counters["backspaces"] ?: 0L,
            suggestionsShown = counters["suggestions_shown"] ?: 0L,
            suggestionsAccepted = counters["suggestions_accepted"] ?: 0L,
            autocorrectReverts = counters["autocorrect_reverts"] ?: 0L,
            topWords = words.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value },
            languageCounts = counters.filterKeys { it.startsWith("lang_") }
                .mapKeys { it.key.removePrefix("lang_") }
                .mapValues { it.value.toInt() }
        )
    }

    @Synchronized
    fun clear() {
        scope.cancel()
        prefs.edit().clear().apply()
        counters.clear()
        words.clear()
    }
}
