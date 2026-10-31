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
     * Returns a snapshot of all known user-word scores as a map of
     * plaintext-word → score in [0, 1].
     *
     * Used by [com.noircipher.shade.engine.UserVocabularyInjector] to merge
     * user-learned words into the neural model's predictions.
     */
    @Synchronized
    fun snapshot(): Map<String, Float> {
        val out = HashMap<String, Float>(prefs.all.size)
        for ((k, v) in prefs.all) {
            if (v !is Int && v !is Long && v !is Float) continue
            val word = k.removePrefix("w:")
            if (word.isEmpty()) continue
            val raw = when (v) {
                is Int -> v.toFloat()
                is Long -> v.toFloat()
                is Float -> v
                else -> continue
            }
            if (raw <= 0f) continue
            out[word] = (raw / 25f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun key(word: String): String = "w:" + word.trim().lowercase()
}
