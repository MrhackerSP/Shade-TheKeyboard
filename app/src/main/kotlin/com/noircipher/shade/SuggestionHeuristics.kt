package com.noircipher.shade

/**
 * Pure suggestion/clipboard heuristics extracted from ShadeService so they are
 * unit-testable on the JVM. No service state involved.
 */
internal object SuggestionHeuristics {

    private val sensitiveKeyRegex = Regex("""(pass(word)?|pwd|token|secret|api[_-]?key|otp|pin)\s*[:=]""")

    /**
     * True when text looks like a password, token or secret. Such text is
     * never learned, never exported, never promoted to a clipboard chip.
     */
    fun isLikelySensitiveText(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")) return false
        if (sensitiveKeyRegex.containsMatchIn(lower)) return true
        if (!text.contains(" ") && text.length >= 10 && text.any { it.isLetter() } && text.any { it.isDigit() } && text.any { !it.isLetterOrDigit() }) return true
        return false
    }

    /**
     * Smart number prediction. Surfaces short numeric completions after words
     * that commonly precede a quantity (in / costs / at / number / ...).
     *
     * Returns at most 2 candidates, all matching the typed [prefix].
     */
    fun numbersForContext(contextWords: List<String>, prefix: String): List<String> {
        if (prefix.isNotEmpty() && !prefix.all { it.isDigit() }) return emptyList()
        val last = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        return when (last) {
            "in" -> listOf("5", "10", "15", "20", "30").filter { it.startsWith(prefix) }.take(2)
            "costs", "cost", "price", "pay", "paid" -> if (prefix.isEmpty()) listOf("$") else emptyList()
            // "it" reads both ways — "it costs $20" and "it takes 20 minutes":
            // dollar sign while nothing is typed, then the numeric pool.
            "it" -> if (prefix.isEmpty()) listOf("$")
                    else listOf("5", "10", "15", "20", "30").filter { it.startsWith(prefix) }.take(2)
            "at", "around", "about" -> listOf("5", "10", "20", "50").filter { it.startsWith(prefix) }.take(2)
            "number", "no." -> listOf("1", "2", "3").filter { it.startsWith(prefix) }.take(2)
            else -> emptyList()
        }
    }
}
