package com.noircipher.shade.engine

import android.content.Context

class UserWordMemory(context: Context) {
    private val prefs = context.getSharedPreferences("shade_user_word_memory", Context.MODE_PRIVATE)

    @Synchronized
    fun bump(word: String) {
        val key = key(word)
        val next = (prefs.getInt(key, 0) + 1).coerceAtMost(200)
        prefs.edit().putInt(key, next).apply()
    }

    @Synchronized
    fun penalize(word: String) {
        val key = key(word)
        val next = (prefs.getInt(key, 0) - 3).coerceAtLeast(-50)
        prefs.edit().putInt(key, next).apply()
    }

    @Synchronized
    fun score(word: String): Float {
        val value = prefs.getInt(key(word), 0)
        if (value <= 0) return 0f
        return (value / 25f).coerceIn(0f, 1f)
    }

    /**
     * Snapshot of all user words with positive scores. Returns a fresh HashMap
     * copy so callers can iterate without holding the prefs lock.
     *
     * Returned map: word (lowercased) -> score in [0, 1].
     */
    @Synchronized
    fun snapshot(): Map<String, Float> {
        val out = HashMap<String, Float>()
        for ((k, v) in prefs.all) {
            if (v !is Int && v !is Long && v !is Float) continue
            val value = when (v) {
                is Int -> v.toFloat()
                is Long -> v.toFloat()
                is Float -> v
                else -> continue
            }
            if (value <= 0) continue
            // Decode the prefs key — it's stored as "w:<word>" so we just
            // strip the prefix; no encryption layer on this prefs file.
            val word = if (k.startsWith("w:")) k.substring(2).lowercase() else k.lowercase()
            if (word.isEmpty() || word.length > 32) continue
            out[word] = (value / 25f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun key(word: String): String = "w:" + word.trim().lowercase()
}
