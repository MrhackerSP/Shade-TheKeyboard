package com.noircipher.shade.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped boost for words/bigrams the user has typed during the
 * current IME session (since [reset]).
 *
 * Unlike the persisted [AdaptiveWordMemory], this is short-lived and is
 * cleared on service destroy / input finish.  Its purpose is to give recent
 * words a temporary ranking boost so the user can quickly re-type words from
 * the same conversation.
 *
 * Thread-safe via [ConcurrentHashMap].
 */
class SessionBigramBoost {

    private val unigramCounts = ConcurrentHashMap<String, Int>()
    private val bigramCounts = ConcurrentHashMap<String, Int>()
    private var lastWord: String? = null

    /** Records a word committed by the user during this session. */
    fun observe(word: String) {
        val w = word.lowercase().trim()
        if (w.isEmpty()) return
        unigramCounts[w] = (unigramCounts[w] ?: 0) + 1
        val prev = lastWord
        if (prev != null) {
            val key = "$prev\u0000$w"
            bigramCounts[key] = (bigramCounts[key] ?: 0) + 1
        }
        lastWord = w
    }

    /**
     * Session-bigram score in [0, 1]: how strongly [word] follows [prev]
     * in this session.  Returns 0 when no observations have been made for
     * this pair yet.
     */
    fun score(prev: String?, word: String): Float {
        val w = word.lowercase().trim()
        if (w.isEmpty() || prev == null) {
            val count = unigramCounts[w] ?: 0
            return if (count <= 0) 0f else (count.toFloat() / (count + 6f)).coerceIn(0f, 1f)
        }
        val key = "${prev.lowercase().trim()}\u0000$w"
        val bigramCount = bigramCounts[key] ?: 0
        if (bigramCount <= 0) return 0f
        val totalPrev = unigramCounts[prev.lowercase().trim()] ?: 0
        if (totalPrev <= 0) return 0f
        return (bigramCount.toFloat() / totalPrev).coerceIn(0f, 1f)
    }

    /** Same as [score] but takes the previous word from the caller's input. */
    fun bigramScore(prev: String?, word: String): Float = score(prev, word)

    /** Number of unique words observed this session. */
    fun unigramSize(): Int = unigramCounts.size

    /** Number of unique bigrams observed this session. */
    fun bigramSize(): Int = bigramCounts.size

    fun reset() {
        unigramCounts.clear()
        bigramCounts.clear()
        lastWord = null
    }
}
