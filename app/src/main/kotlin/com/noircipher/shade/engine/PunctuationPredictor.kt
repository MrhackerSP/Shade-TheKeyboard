package com.noircipher.shade.engine

/**
 * #24 — Punctuation predictor.
 *
 * Suggests punctuation to follow the current context — e.g. "Hello" → ",",
 * "What" → "?". Used as a soft hint by the suggestion bar; callers decide
 * whether to surface the hint or not.
 */
object PunctuationPredictor {

    private val direct: Map<String, String> = mapOf(
        "hello" to ",",
        "hi" to ",",
        "hey" to ",",
        "dear" to ",",
        "thanks" to ".",
        "thank you" to ".",
        "thankyou" to ".",
        "ok" to ".",
        "okay" to ".",
        "sure" to ".",
        "yes" to ".",
        "no" to ".",
        "yep" to ".",
        "nope" to ".",
        "got it" to ".",
        "gotcha" to ".",
        "cool" to ".",
        "great" to ".",
        "awesome" to ".",
        "perfect" to ".",
        "bye" to "!",
        "goodbye" to "!",
        "good night" to "!",
        "goodnight" to "!",
        "wow" to "!",
        "yay" to "!",
        "woohoo" to "!"
    )

    private val questionStarters: Set<String> = setOf(
        "what", "whats", "what's", "where", "where's", "when", "when's",
        "why", "why's", "who", "who's", "how", "how's", "is", "are", "was",
        "were", "do", "does", "did", "can", "could", "would", "should",
        "will", "shall", "have", "has", "had", "am", "may", "might", "must",
        "whose", "which", "whom"
    )

    /**
     * @return a punctuation character to follow the last context word,
     *   or null when no suggestion applies.
     */
    fun suggestPunctuation(contextWords: List<String>): String? {
        if (contextWords.isEmpty()) return null
        val last = contextWords.last().trim().trimEnd('.', ',', '!', '?', ':', ';').lowercase()
        if (last.isEmpty()) return null
        // Direct match wins first.
        direct[last]?.let { return it }
        // 2-word match (e.g. "good night")
        if (contextWords.size >= 2) {
            val two = "${contextWords[contextWords.size - 2].lowercase()} $last"
            direct[two]?.let { return it }
        }
        // Question word → "?"
        if (questionStarters.contains(last)) return "?"
        return null
    }
}
