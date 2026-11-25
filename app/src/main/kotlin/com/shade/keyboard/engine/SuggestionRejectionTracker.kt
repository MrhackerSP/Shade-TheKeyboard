package com.shade.keyboard.engine

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
    // §3 — when each word was last rejected: counts older than
    // [REJECTION_TTL_MS] have decayed away and stop suppressing, so a word
    // rejected this morning doesn't stay filtered all afternoon.
    private val lastRejectedAt = ConcurrentHashMap<String, Long>()

    /** Time source; replaceable in tests. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    // Words the user explicitly removed via the long-press dialog on the suggestion strip.
    // Suppressed immediately for the whole session, without needing N implicit rejections.
    private val suppressed = ConcurrentHashMap.newKeySet<String>()

    fun recordRejection(word: String) {
        if (word.isEmpty()) return
        val w = word.trim().lowercase()
        if (counts.size >= 2000) {
            counts.clear() // bounded: don't grow for the whole service lifetime
            lastRejectedAt.clear()
        }
        counts.computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
        lastRejectedAt[w] = clock()
    }

    /**
     * Explicitly removes [word] from the suggestion bar: suppressed immediately and for the
     * rest of the session (used by the "Remove suggestion" dialog).
     */
    fun suppress(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return
        if (suppressed.size >= 2000) suppressed.clear()
        suppressed.add(w)
        counts[w] = AtomicInteger(DEFAULT_THRESHOLD)
    }

    fun shouldSuppress(word: String): Boolean {
        if (word.isEmpty()) return false
        val w = word.trim().lowercase()
        if (suppressed.contains(w)) return true
        // §3 — time decay: expired rejection counts are dropped on read, so
        // the word must re-accumulate rejections to be suppressed again.
        val at = lastRejectedAt[w] ?: return false
        if (clock() - at > REJECTION_TTL_MS) {
            counts.remove(w)
            lastRejectedAt.remove(w)
            return false
        }
        val c = counts[w]?.get() ?: 0
        return c >= threshold
    }

    fun filter(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.filterNot { shouldSuppress(it) }
    }

    fun clearSession() {
        counts.clear()
        lastRejectedAt.clear()
        suppressed.clear()
    }

    fun setThreshold(value: Int) {
        threshold = value.coerceAtLeast(1)
    }

    @Suppress("unused")
    private val ctx: Context = context.applicationContext

    companion object {
        private const val DEFAULT_THRESHOLD = 3
        // §3 — rejections decay after 10 minutes instead of accumulating for
        // the whole session.
        private const val REJECTION_TTL_MS = 10L * 60L * 1000L
    }
}
