package com.noircipher.shade.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * #8 — Session bigram boost.
 *
 * Tracks words typed within the current IME session and gives a small
 * score boost to candidates the user has used recently. The boost is
 * *session-scoped* — clearing on app exit, language switch, or explicit
 * `clear()` — so it doesn't leak into long-term personalization.
 *
 * The bigram version remembers "(prevWord, word)" co-occurrence counts.
 */
class SessionBigramBoost {

    private val unigram = ConcurrentHashMap<String, AtomicInteger>()
    private val bigram = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()
    private var totalWords = 0

    fun observe(word: String) {
        if (word.isBlank()) return
        val w = word.trim().lowercase()
        unigram.computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
        totalWords += 1
    }

    fun observeBigram(prev: String?, word: String) {
        if (word.isBlank()) return
        val w = word.trim().lowercase()
        if (prev.isNullOrBlank()) {
            observe(w)
            return
        }
        val p = prev.trim().lowercase()
        unigram.computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
        bigram.computeIfAbsent(p) { ConcurrentHashMap() }
            .computeIfAbsent(w) { AtomicInteger(0) }.incrementAndGet()
        totalWords += 1
    }

    /**
     * Unigram-based boost in [0, 1]. Returns 0 if we've never observed [word].
     */
    fun score(word: String): Float {
        if (totalWords == 0) return 0f
        val w = word.trim().lowercase()
        val count = unigram[w]?.get() ?: 0
        if (count == 0) return 0f
        // Sublinear so a word repeated 50 times doesn't dominate.
        val raw = kotlin.math.ln(count + 1f) / kotlin.math.ln(totalWords + 2f)
        return raw.coerceIn(0f, 1f)
    }

    /**
     * Bigram conditional boost P(word | prev) in [0, 1].
     */
    fun bigramScore(prev: String?, word: String): Float {
        if (prev.isNullOrBlank()) return 0f
        val p = prev.trim().lowercase()
        val w = word.trim().lowercase()
        val row = bigram[p] ?: return 0f
        val pairCount = row[w]?.get() ?: 0
        if (pairCount == 0) return 0f
        val totalCount = unigram[p]?.get() ?: return 0f
        if (totalCount == 0) return 0f
        return (pairCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    }

    fun clear() {
        unigram.clear()
        bigram.clear()
        totalWords = 0
    }
}
