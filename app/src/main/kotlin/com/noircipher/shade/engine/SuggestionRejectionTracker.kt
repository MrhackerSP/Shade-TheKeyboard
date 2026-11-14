package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
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
 * Words explicitly removed via the long-press "Remove suggestion" dialog go
 * into a persistent blocklist ([block]) and never come back, across sessions.
 *
 * Session rejection counts live in memory only and are cleared on
 * [clearSession] / IME teardown.
 */
class SuggestionRejectionTracker(context: Context) {

    @Volatile
    private var threshold: Int = DEFAULT_THRESHOLD
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val blockPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile
    private var blocked: Set<String> = loadBlocked()

    fun recordRejection(word: String) {
        if (word.isEmpty()) return
        val w = word.trim().lowercase()
        if (counts.size >= 2000) counts.clear() // bounded: don't grow for the whole service lifetime
        counts.computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
    }

    /** Persistently removes [word] from future suggestions (long-press "Remove suggestion"). */
    fun block(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return
        synchronized(this) {
            val next = blocked + w
            blocked = next
            blockPrefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        }
        // A blocked word must also stop being pushed back by the learned-word memory.
        counts[w] = AtomicInteger(threshold)
    }

    fun unblock(word: String) {
        val w = word.trim().lowercase()
        synchronized(this) {
            val next = blocked - w
            blocked = next
            blockPrefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        }
    }

    fun isBlocked(word: String): Boolean {
        if (word.isEmpty()) return false
        return blocked.contains(word.trim().lowercase())
    }

    fun shouldSuppress(word: String): Boolean {
        if (word.isEmpty()) return false
        val w = word.trim().lowercase()
        if (blocked.contains(w)) return true
        val c = counts[w]?.get() ?: 0
        return c >= threshold
    }

    fun filter(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.filterNot { shouldSuppress(it) }
    }

    private fun loadBlocked(): Set<String> =
        blockPrefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

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
        private const val PREFS = "shade_blocked_suggestions"
        private const val KEY_BLOCKED = "words"
    }
}
