package com.noircipher.shade.engine

/**
 * Carries light context across sentence boundaries so the next sentence
 * gets a sensible "starter" suggestion even after a period.
 *
 * Tracks:
 *   - The most recently completed sentence's last 1–2 words (for chaining).
 *   - Whether the previous sentence was a question (for "Yes/No" completions).
 *   - The topic keywords (top-N most frequent nouns) seen recently.
 *
 * [extendContext] is called after a sentence-ending punctuation is committed.
 * [sentenceStarters] returns the suggested next-word(s) given the current
 * context.
 *
 * The implementation is intentionally lightweight (in-memory, not persisted):
 * cross-sentence context is per-conversation, not per-user.
 */
class CrossSentenceContext {

    private val recentWords = ArrayDeque<String>()
    private var lastSentenceWasQuestion = false
    private var lastTopic: String? = null
    private var lastSentenceEndedAt = 0L

    fun extendContext(sentenceTail: List<String>, endedWithQuestion: Boolean, now: Long = System.currentTimeMillis()) {
        lastSentenceWasQuestion = endedWithQuestion
        lastSentenceEndedAt = now
        lastTopic = sentenceTail.lastOrNull { it.length > 3 }?.lowercase()
        recentWords.clear()
        sentenceTail.takeLast(2).forEach { recentWords.addLast(it.lowercase()) }
    }

    /** Words whose presence in the previous sentence suggests the next word. */
    private val QUESTION_FOLLOWUPS = mapOf(
        "what" to listOf("i", "you", "we", "not", "do", "really"),
        "why" to listOf("because", "i", "you", "not", "since"),
        "when" to listOf("tomorrow", "today", "soon", "later", "now"),
        "where" to listOf("here", "there", "at", "in", "near"),
        "how" to listOf("by", "with", "i", "you", "we"),
        "who" to listOf("me", "you", "they", "we", "i")
    )

    private val QUESTION_ANSWERS = listOf("yes", "no", "maybe", "sure", "ok", "of course", "not really")

    /**
     * Returns suggested first-words for the next sentence.  When the previous
     * sentence was a question, this favours yes/no/maybe style openers.
     * Otherwise, it suggests context-appropriate follow-up words.
     */
    fun sentenceStarters(): List<String> {
        if (recentWords.isEmpty()) {
            // No context yet — suggest a generic opener.
            return listOf("i", "you", "the", "so", "but", "and")
        }
        if (lastSentenceWasQuestion) return QUESTION_ANSWERS

        val prev = recentWords.last()
        QUESTION_FOLLOWUPS[prev]?.let { return it }

        // Default sentence starters.
        return when {
            prev in setOf("yes", "ok", "sure", "yeah") -> listOf("i", "we", "let's", "so")
            prev in setOf("no", "nope", "nah") -> listOf("i", "we", "let's", "but")
            prev in setOf("thanks", "thank", "thx") -> listOf("you're", "no", "any", "sure")
            else -> listOf("i", "you", "the", "so", "but", "and", "we")
        }
    }

    fun reset() {
        recentWords.clear()
        lastSentenceWasQuestion = false
        lastTopic = null
        lastSentenceEndedAt = 0L
    }

    fun lastTopicWord(): String? = lastTopic
}
