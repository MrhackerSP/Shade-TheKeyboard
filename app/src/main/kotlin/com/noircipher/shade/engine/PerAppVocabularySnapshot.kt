package com.noircipher.shade.engine

/**
 * #20 — Per-app vocabulary snapshot.
 *
 * Wraps an [AppContextModel] to provide fast lookups of the most-frequent
 * words for the current app, or for a given prefix within the current app.
 *
 * Tracks the current app hash so that switching apps triggers a fresh
 * snapshot fetch. The underlying model handles persistence.
 */
class PerAppVocabularySnapshot(private val appContextModel: AppContextModel) {

    private var currentAppHash: String = ""

    fun onEnterApp(appHash: String) {
        if (appHash == currentAppHash) return
        currentAppHash = appHash
    }

    /**
     * Top-K words for the current app. We don't keep a materialized list
     * here — we just iterate the model. The model's internal map is keyed
     * on the current hash, so we ask it to score a sliding candidate list.
     *
     * For simplicity, returns the empty list when no app is active.
     */
    fun topWords(): List<String> {
        if (currentAppHash.isEmpty()) return emptyList()
        return appContextModel.topWordsForCurrentApp("", 5)
    }

    /**
     * Returns up to [limit] words for [prefix] that the user has used
     * frequently in the current app. Returns empty if no app is active.
     */
    fun wordsForPrefix(prefix: String, limit: Int = 5): List<String> {
        if (currentAppHash.isEmpty()) return emptyList()
        return appContextModel.topWordsForCurrentApp(prefix, limit)
    }

    /**
     * Score an individual candidate word in the current app. Used by
     * callers that already have a candidate list and want to bias it.
     */
    fun score(candidate: String): Float {
        if (currentAppHash.isEmpty()) return 0f
        return appContextModel.score(candidate)
    }
}
