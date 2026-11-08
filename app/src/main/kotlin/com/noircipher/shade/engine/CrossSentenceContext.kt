package com.noircipher.shade.engine

/**
 * #7 — Cross-sentence context.
 *
 * Walks back through the last few words to figure out whether we're at the
 * start of a new sentence (which means we should bias toward sentence-starter
 * phrases and capitalize the next word).
 */
object CrossSentenceContext {

    private const val MAX_CONTEXT = 6
    private val sentenceTerminatorChars = charArrayOf('.', '!', '?')

    private val sentenceStarters: List<String> = listOf(
        "the", "I", "I'm", "I'll", "I've", "I'd", "we", "we'll", "we've",
        "you", "you'll", "you've", "they", "they'll", "they've",
        "this", "that", "those", "these",
        "how", "what", "where", "when", "why", "who", "which",
        "let's", "let me", "let us",
        "it", "it's", "it'll", "it would",
        "my", "your", "their", "our", "his", "her",
        "hey", "hello", "hi", "morning", "evening", "dear",
        "hopefully", "basically", "actually", "honestly",
        "so", "but", "and", "or", "because", "although", "however",
        "thanks", "thank you", "sorry", "please",
        "just", "now", "today", "tomorrow", "yesterday",
        "as", "if", "when", "while", "since", "after", "before",
        "hopefully", "assuming", "given", "considering",
        "can", "could", "would", "should", "will", "may", "might",
        "do", "did", "does", "have", "has", "had",
        "is", "are", "was", "were", "be", "been",
        "anyway", "anyways", "alright", "well", "ok", "okay"
    )

    /**
     * Trims the text back to the content after the most recent sentence terminator,
     * so the model sees the current sentence instead of stale earlier text.
     */
    fun truncateAtSentenceBoundary(text: String): String {
        val raw = text.trim()
        if (raw.isEmpty()) return ""
        var lastTerminator = -1
        for (i in raw.indices) {
            if (raw[i] !in sentenceTerminatorChars) continue
            // "5.99", "example.com", "e.g" - a terminator glued to the next character is not a sentence end.
            if (i + 1 < raw.length && !raw[i + 1].isWhitespace()) continue
            lastTerminator = i
        }
        if (lastTerminator < 0) return raw
        return raw.substring(lastTerminator + 1).trimStart()
    }

    /**
     * Sentence-start check on the RAW text before the typed word. [isSentenceStart] works on word
     * lists whose punctuation was already stripped by the caller's tokenizer, so it rarely fires.
     */
    fun endsWithSentenceTerminator(rawBefore: String): Boolean {
        val t = rawBefore.trimEnd()
        return t.isEmpty() || t.last() in sentenceTerminatorChars
    }

    /**
     * Extend the given context words by also peeking at the raw text before
     * the cursor — useful when [contextWords] is partial (e.g. typed-word
     * was stripped). Returns the merged lower-cased list, capped at
     * [MAX_CONTEXT] items.
     */
    fun extendContext(contextWords: List<String>): List<String> {
        if (contextWords.isEmpty()) return emptyList()
        return contextWords.takeLast(MAX_CONTEXT)
    }

    /**
     * Returns true if the context is empty (very start of input) OR the most
     * recent sentence terminator was a `.`, `!`, or `?` followed by optional
     * whitespace. In that case we're at the start of a new sentence.
     */
    fun isSentenceStart(contextWords: List<String>): Boolean {
        if (contextWords.isEmpty()) return true
        val tail = contextWords.takeLast(2)
        return tail.any { word ->
            val trimmed = word.trim()
            trimmed.isNotEmpty() && trimmed.lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true
        }
    }

    fun shouldCapitalize(contextWords: List<String>): Boolean = isSentenceStart(contextWords)

    /**
     * Sentence-starter suggestions that begin with [prefix].
     */
    fun sentenceStarters(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return sentenceStarters.take(limit)
        val lower = prefix.lowercase()
        return sentenceStarters
            .filter { it.startsWith(prefix, ignoreCase = true) || it.lowercase().startsWith(lower) }
            .take(limit)
    }
}
