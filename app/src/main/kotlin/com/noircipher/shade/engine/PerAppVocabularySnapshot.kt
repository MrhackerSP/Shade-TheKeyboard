package com.noircipher.shade.engine

import android.content.Context
import android.util.LruCache

/**
 * Pre-loads the top-N words for each app the user types in, so suggestions can
 * be biased toward app-specific vocabulary without paying the cost of a
 * per-keystroke SharedPreferences lookup.
 *
 * The snapshot is a small in-memory cache keyed by the (SHA-256-hashed)
 * package name.  On a package change, the snapshot is populated from the
 * persisted [AppContextModel] SharedPreferences store.
 */
class PerAppVocabularySnapshot(private val appContextModel: AppContextModel) {

    private val cache = LruCache<String, List<String>>(MAX_APPS)

    /** Marks [packageHash] as the active app; pre-loads its top words. */
    fun activate(packageHash: String, topWords: List<String>) {
        if (packageHash.isEmpty()) return
        cache.put(packageHash, topWords.distinct().take(MAX_WORDS_PER_APP))
    }

    /**
     * Returns the top-N words for [packageHash] that start with [prefix], or
     * an empty list when no snapshot is available.
     */
    fun wordsForPrefix(packageHash: String, prefix: String): List<String> {
        if (packageHash.isEmpty() || prefix.isEmpty()) return emptyList()
        val snapshot = cache.get(packageHash) ?: return emptyList()
        val p = prefix.lowercase()
        return snapshot.filter { it.startsWith(p) }
    }

    /** Returns true when a snapshot for [packageHash] is loaded. */
    fun hasSnapshot(packageHash: String): Boolean =
        packageHash.isNotEmpty() && cache.get(packageHash) != null

    fun reset() {
        cache.evictAll()
    }

    companion object {
        private const val MAX_APPS = 8
        private const val MAX_WORDS_PER_APP = 60
    }
}
