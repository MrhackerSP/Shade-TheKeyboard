package com.noircipher.shade.engine

/**
 * Multi-word completion based on common bigram continuations.
 *
 * When the user has typed a complete word (followed by space), this returns
 * the most likely next word(s) based on a static table of frequently-adjacent
 * pairs.  This complements the learned [BigramModel] with a baseline that
 * works on the very first install, before any user data has been collected.
 *
 * Examples: "as a" → ["matter"], "thank" → ["you"], "see" → ["you"].
 *
 * Returns an empty list when nothing matches, so callers can fall through to
 * other predictors (neural / bigram / dictionary).
 */
object PhraseCompletion {

    private val BIGRAMS: Map<String, List<String>> = mapOf(
        "as" to listOf("a", "well", "soon", "if", "long", "much", "many"),
        "i" to listOf("am", "have", "will", "was", "think", "know", "need"),
        "i'm" to listOf("going", "not", "so", "sorry", "happy", "glad", "here"),
        "i'll" to listOf("be", "let", "see", "get", "have", "go"),
        "i've" to listOf("been", "got", "seen", "never", "always", "had"),
        "i'd" to listOf("like", "love", "be", "have", "say", "go"),
        "you" to listOf("know", "can", "are", "have", "should", "want", "need"),
        "you're" to listOf("welcome", "right", "the", "so", "going", "not"),
        "you'll" to listOf("be", "have", "see", "get", "love", "find"),
        "you've" to listOf("got", "been", "seen", "done", "made", "had"),
        "you'd" to listOf("be", "have", "think", "like", "love"),
        "he" to listOf("is", "was", "has", "had", "will", "said"),
        "she" to listOf("is", "was", "has", "had", "will", "said"),
        "it" to listOf("is", "was", "will", "has", "had", "looks"),
        "it's" to listOf("a", "not", "been", "going", "so", "just"),
        "we" to listOf("are", "have", "can", "will", "were", "should"),
        "we're" to listOf("going", "not", "here", "in", "on", "just"),
        "we'll" to listOf("be", "see", "have", "get", "go"),
        "we've" to listOf("been", "got", "had", "seen", "done"),
        "they" to listOf("are", "have", "will", "were", "say"),
        "they're" to listOf("going", "not", "here", "the", "so"),
        "they'll" to listOf("be", "have", "see", "get"),
        "they've" to listOf("been", "got", "had"),
        "thank" to listOf("you"),
        "thanks" to listOf("for", "to", "a", "again"),
        "good" to listOf("morning", "night", "luck", "job", "idea", "to"),
        "see" to listOf("you", "what", "if", "that", "how"),
        "let" to listOf("me", "us", "it", "them", "him", "her"),
        "let's" to listOf("go", "see", "get", "do", "make", "try"),
        "how" to listOf("are", "do", "about", "much", "many", "long"),
        "how's" to listOf("it", "that", "your", "the", "going"),
        "what" to listOf("is", "are", "do", "about", "if", "time"),
        "what's" to listOf("up", "the", "going", "your", "this"),
        "when" to listOf("you", "i", "we", "the", "do", "can"),
        "where" to listOf("are", "is", "you", "we", "do", "can"),
        "why" to listOf("are", "is", "do", "did", "not", "you"),
        "who" to listOf("is", "are", "was", "were", "knows", "did"),
        "which" to listOf("is", "are", "one", "way", "of"),
        "this" to listOf("is", "was", "time", "one", "way"),
        "that" to listOf("is", "was", "would", "could", "the", "one"),
        "that's" to listOf("why", "right", "true", "all", "not", "great"),
        "there" to listOf("is", "are", "was", "were"),
        "there's" to listOf("no", "a", "the", "no", "been"),
        "here" to listOf("is", "are", "we", "you", "comes"),
        "here's" to listOf("the", "a", "what", "how"),
        "have" to listOf("to", "a", "been", "got", "some"),
        "has" to listOf("been", "to", "a", "the", "no"),
        "had" to listOf("to", "a", "been", "no", "the"),
        "do" to listOf("you", "not", "it", "we", "they"),
        "does" to listOf("not", "it", "the", "he", "she"),
        "did" to listOf("you", "not", "it", "they", "we"),
        "can" to listOf("you", "i", "we", "be", "not", "do"),
        "could" to listOf("you", "be", "not", "have", "go"),
        "would" to listOf("you", "be", "have", "like", "not"),
        "should" to listOf("you", "be", "have", "not", "go"),
        "will" to listOf("be", "you", "not", "have", "do"),
        "won't" to listOf("be", "have", "work", "do"),
        "just" to listOf("a", "the", "got", "want", "need", "like"),
        "very" to listOf("much", "good", "nice", "kind", "well"),
        "so" to listOf("much", "many", "that", "good", "far"),
        "too" to listOf("much", "many", "late", "bad", "long"),
        "a" to listOf("lot", "little", "few", "bit", "couple"),
        "a lot" to listOf("of", "more", "better"),
        "kind" to listOf("of"),
        "sort" to listOf("of"),
        "of" to listOf("the", "a", "course", "all"),
        "to" to listOf("the", "be", "do", "go", "get", "have"),
        "for" to listOf("the", "you", "me", "now", "sure"),
        "in" to listOf("the", "a", "fact", "case"),
        "on" to listOf("the", "my", "your", "it", "top"),
        "at" to listOf("the", "least", "all", "home", "first"),
        "with" to listOf("the", "me", "you", "it", "that"),
        "out" to listOf("of", "there", "to", "about"),
        "up" to listOf("to", "with", "for", "and"),
        "all" to listOf("the", "of", "right", "good"),
        "any" to listOf("time", "way", "more", "thing"),
        "some" to listOf("of", "time", "more", "people"),
        "no" to listOf("one", "way", "more", "longer", "matter"),
        "not" to listOf("sure", "now", "really", "yet", "only"),
        "really" to listOf("good", "nice", "appreciate", "want", "like"),
        "very" to listOf("much", "good", "nice", "well", "kind"),
        "much" to listOf("better", "more", "appreciated", "fun"),
        "talk" to listOf("to", "soon", "later"),
        "speak" to listOf("to", "with", "about"),
        "let" to listOf("me", "us"),
        "looking" to listOf("forward", "for", "at"),
        "looking forward" to listOf("to"),
        "get" to listOf("to", "back", "going", "ready"),
        "got" to listOf("to", "it", "back", "the"),
        "make" to listOf("sure", "it", "a", "the"),
        "feel" to listOf("free", "better", "good"),
        "sounds" to listOf("good", "great", "like"),
        "looks" to listOf("good", "like", "great"),
        "happy" to listOf("birthday", "to", "new"),
        "best" to listOf("of", "wishes", "regards"),
        "kind" to listOf("regards")
    )

    /**
     * Returns phrase completions for the given preceding [token].
     * The token should be lowercased before lookup.  Returns at most [limit]
     * candidates.
     */
    fun suggestPhraseCompletions(token: String?, limit: Int = 3): List<String> {
        if (token == null || limit <= 0) return emptyList()
        val key = token.lowercase().trim()
        if (key.isEmpty()) return emptyList()
        return BIGRAMS[key]?.take(limit) ?: emptyList()
    }
}
