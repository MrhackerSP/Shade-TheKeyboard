package com.noircipher.shade.engine

import android.util.LruCache

/**
 * Small LRU cache for suggestion-bar results.
 *
 * Computing suggestions for a given (prefix, context) is expensive — the
 * pipeline touches the dictionary, bigram model, neural model, and adaptive
 * stores.  When the user backspaces and re-types (or rotates the device),
 * the same input can be re-evaluated.  This cache stores the most recent
 * results keyed by a hash of (prefix, prev1, prev2, locale, packageHash).
 *
 * The cache is intentionally small (32 entries) and entry values are shallow
 * copies — they should be treated as read-only.
 */
class SuggestionCache(maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Key(
        val prefix: String,
        val prev1: String?,
        val prev2: String?,
        val locale: String,
        val packageHash: String
    )

    private val cache = LruCache<Key, List<String>>(maxEntries)

    /** Stores [candidates] for the given context. */
    fun put(
        prefix: String,
        prev1: String?,
        prev2: String?,
        locale: String,
        packageHash: String,
        candidates: List<String>
    ) {
        if (candidates.isEmpty()) return
        cache.put(Key(prefix.lowercase(), prev1?.lowercase(), prev2?.lowercase(), locale, packageHash), ArrayList(candidates))
    }

    /**
     * Returns the cached candidates, or null when there is no entry for this
     * context.  Callers must check the return value before using it.
     */
    fun get(
        prefix: String,
        prev1: String?,
        prev2: String?,
        locale: String,
        packageHash: String
    ): List<String>? {
        return cache.get(Key(prefix.lowercase(), prev1?.lowercase(), prev2?.lowercase(), locale, packageHash))
    }

    fun clear() {
        cache.evictAll()
    }

    /** Number of entries currently cached. */
    fun size(): Int = cache.size()

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 32
    }
}
