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

    private fun key(word: String): String = "w:" + word.trim().lowercase()
}
