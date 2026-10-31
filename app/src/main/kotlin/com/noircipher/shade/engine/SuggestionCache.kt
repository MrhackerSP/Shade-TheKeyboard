package com.noircipher.shade.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * #11 — Suggestion cache.
 *
 * Memoizes suggestion-list lookups by (prev2, prev1, prefix). Short TTL
 * keeps the cache from getting stale as the user types.
 *
 * Thread-safe via ConcurrentHashMap. Lookups are O(1) and very cheap, so
 * this can be hit on every keystroke.
 */
object SuggestionCache {

    private const val TTL_MS = 1500L
    private const val MAX_ENTRIES = 256

    private data class Entry(
        val candidates: List<String>,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun key(prev2: String?, prev1: String?, prefix: String): String {
        val sb = StringBuilder(24)
        sb.append(prev2 ?: "").append('\u0001')
        sb.append(prev1 ?: "").append('\u0001')
        sb.append(prefix)
        return sb.toString()
    }

    fun get(prev2: String?, prev1: String?, prefix: String): List<String>? {
        val k = key(prev2, prev1, prefix)
        val e = cache[k] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(k)
            return null
        }
        return e.candidates
    }

    fun put(prev2: String?, prev1: String?, prefix: String, candidates: List<String>) {
        val k = key(prev2, prev1, prefix)
        if (cache.size >= MAX_ENTRIES) {
            // Drop one expired entry to make room; if none expired, drop the
            // oldest by expiry (linear scan but bounded by MAX_ENTRIES).
            var oldestKey: String? = null
            var oldestExp = Long.MAX_VALUE
            val now = System.currentTimeMillis()
            for ((ek, ev) in cache) {
                if (now > ev.expiresAt) {
                    cache.remove(ek)
                    if (cache.size < MAX_ENTRIES) break
                }
                if (ev.expiresAt < oldestExp) {
                    oldestExp = ev.expiresAt
                    oldestKey = ek
                }
            }
            oldestKey?.let { cache.remove(it) }
        }
        cache[k] = Entry(candidates, System.currentTimeMillis() + TTL_MS)
    }

    fun invalidate() {
        cache.clear()
    }

    fun invalidatePrefix(prefix: String) {
        if (prefix.isEmpty()) {
            invalidate()
            return
        }
        val toRemove = cache.keys.filter { it.endsWith("\u0001$prefix") }
        for (k in toRemove) cache.remove(k)
    }
}
