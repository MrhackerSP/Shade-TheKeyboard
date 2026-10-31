package com.noircipher.shade.engine

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * #14 — Suggestion rejection tracker.
 *
 * When the user types a word that was previously shown in the suggestion bar
 * (and they ignored it in favor of their own typing), we record a rejection
 * against that suggestion. Once a suggestion has been rejected N times
 * recently, it gets suppressed from future suggestions.
 *
 * Session-scoped: counts live in memory only and are cleared on
 * [clearSession] / IME teardown.
 */
class SuggestionRejectionTracker(context: Context) {

    @Volatile
    private var threshold: Int = DEFAULT_THRESHOLD
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun recordRejection(word: String) {
        if (word.isEmpty()) return
        val w = word.trim().lowercase()
        counts.computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
    }

    fun shouldSuppress(word: String): Boolean {
        if (word.isEmpty()) return false
        val w = word.trim().lowercase()
        val c = counts[w]?.get() ?: 0
        return c >= threshold
    }

    fun filter(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.filterNot { shouldSuppress(it) }
    }

    fun clearSession() {
        counts.clear()
    }

    fun setThreshold(value: Int) {
        threshold = value.coerceAtLeast(1)
    }

    @Suppress("unused")
    private val ctx: Context = context.applicationContext

    companion object {
        private const val DEFAULT_THRESHOLD = 3
    }
}
