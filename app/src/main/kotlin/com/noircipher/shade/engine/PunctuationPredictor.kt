package com.noircipher.shade.engine

/**
 * Predicts the most likely next punctuation mark based on the previous token
 * and overall sentence state.
 *
 * Examples:
 *   "Hello" → ","
 *   "What"  → "?"
 *   "Thanks" → "."
 *   "Hmm"   → "..."
 *
 * Returns the predicted punctuation character (without a leading space), or
 * null when nothing applies.  The caller is responsible for handling the
 * surrounding whitespace (e.g. collapse the existing trailing space and
 * append the punctuation).
 */
object PunctuationPredictor {

    private data class Rule(val token: String, val punctuation: String)

    private val RULES: List<Rule> = listOf(
        // Greetings → comma
        Rule("hi", ","),
        Rule("hello", ","),
        Rule("hey", ","),
        Rule("good", ","),
        Rule("dear", ","),
        Rule("morning", ","),
        Rule("evening", ","),
        Rule("afternoon", ","),
        Rule("night", ","),
        // Question words → question mark
        Rule("what", "?"),
        Rule("why", "?"),
        Rule("when", "?"),
        Rule("where", "?"),
        Rule("who", "?"),
        Rule("how", "?"),
        Rule("which", "?"),
        Rule("whose", "?"),
        Rule("whom", "?"),
        Rule("is", "?"),
        Rule("are", "?"),
        Rule("was", "?"),
        Rule("were", "?"),
        Rule("do", "?"),
        Rule("does", "?"),
        Rule("did", "?"),
        Rule("can", "?"),
        Rule("could", "?"),
        Rule("would", "?"),
        Rule("should", "?"),
        Rule("will", "?"),
        Rule("shall", "?"),
        Rule("may", "?"),
        Rule("might", "?"),
        Rule("have", "?"),
        Rule("has", "?"),
        Rule("had", "?"),
        // Closers → period
        Rule("thanks", "."),
        Rule("thank", "."),
        Rule("appreciate", "."),
        Rule("great", "."),
        Rule("perfect", "."),
        Rule("awesome", "."),
        Rule("cool", "."),
        Rule("nice", "."),
        Rule("ok", "."),
        Rule("okay", "."),
        Rule("agreed", "."),
        Rule("sounds", "."),
        Rule("got", "."),
        Rule("bye", "."),
        Rule("goodbye", "."),
        Rule("later", "."),
        // Hesitation → ellipsis
        Rule("hmm", "..."),
        Rule("umm", "..."),
        Rule("uhh", "..."),
        Rule("well", "..."),
        Rule("so", "..."),
        Rule("actually", "...")
    )

    private val BY_TOKEN: Map<String, String> = RULES.associate { it.token to it.punctuation }

    /**
     * Returns the predicted punctuation for the [previousToken], or null when
     * no rule applies.  The token should be lower-cased and stripped of
     * surrounding whitespace/punctuation before lookup.
     */
    fun predict(previousToken: String?): String? {
        if (previousToken == null) return null
        val key = previousToken.lowercase().trim().trimEnd(',', '.', '?', '!', ';', ':')
        return BY_TOKEN[key]
    }
}
