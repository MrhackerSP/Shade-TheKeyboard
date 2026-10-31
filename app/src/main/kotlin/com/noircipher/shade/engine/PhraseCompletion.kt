package com.noircipher.shade.engine

/**
 * #3 — Multi-word phrase completion.
 *
 * Bigram phrase patterns: when the user has just typed one word and starts
 * typing the next, predict the most common continuation phrase.
 *
 * Examples:
 *   "good" + "m" → "morning" / "luck" / "night"
 *   "thank" + "" → "you" / "you so much" / "you very much"
 *   "how" + "a" → "are" / "about"
 */
object PhraseCompletion {

    private val bigrams: Map<String, List<String>> = mapOf(
        "good" to listOf("morning", "luck", "night", "evening", "afternoon",
            "idea", "job", "thing", "to see", "to hear"),
        "thank" to listOf("you", "you so much", "you very much", "you again", "s"),
        "thanks" to listOf("again", "a lot", "so much", "for the", "for your"),
        "how" to listOf("are", "do", "does", "did", "was", "were", "have",
            "has", "can", "could", "would", "should", "about", "much", "many"),
        "let" to listOf("me", "us", "them", "him", "her", "it"),
        "i'll" to listOf("be", "let", "see", "get", "have", "do", "go", "send"),
        "i'm" to listOf("not", "going", "so", "sorry", "happy", "glad", "sure",
            "fine", "okay", "good", "here"),
        "i've" to listOf("been", "got", "had", "seen", "done", "thought", "heard"),
        "i'd" to listOf("like", "love", "hate", "say", "think", "be", "have"),
        "what" to listOf("is", "are", "do", "did", "was", "were", "about",
            "time", "kind", "sort"),
        "where" to listOf("are", "is", "do", "did", "can", "could", "were"),
        "when" to listOf("are", "is", "do", "did", "can", "could", "would"),
        "why" to listOf("are", "is", "do", "did", "don't", "not"),
        "who" to listOf("is", "are", "was", "were", "did", "do", "called"),
        "happy" to listOf("birthday", "new year", "anniversary", "holidays",
            "to hear", "to see"),
        "merry" to listOf("christmas", "Christmas"),
        "see" to listOf("you", "you soon", "you later", "you tomorrow", "what"),
        "talk" to listOf("to you", "soon", "later", "to you soon", "to you later"),
        "let me" to listOf("know", "see", "think", "check", "try"),
        "let us" to listOf("know", "go", "see", "try"),
        "let's" to listOf("go", "see", "try", "do", "have", "make", "talk"),
        "i am" to listOf("not", "going", "sorry", "happy", "sure", "fine"),
        "have a" to listOf("good", "great", "nice", "wonderful", "lovely"),
        "i hope" to listOf("you", "so", "that"),
        "hope" to listOf("you", "to", "so", "that"),
        "looking" to listOf("forward", "for", "at", "to"),
        "looking forward" to listOf("to", "to seeing", "to hearing"),
        "talk to" to listOf("you", "you soon", "you later"),
        "as" to listOf("soon", "well", "if", "long", "much"),
        "as soon" to listOf("as", "as possible"),
        "as well" to listOf("as", "for"),
        "in" to listOf("the", "a", "case", "spite", "fact", "order"),
        "in case" to listOf("of", "you", "we", "I"),
        "in spite" to listOf("of"),
        "in fact" to listOf("it", "they", "we", "I"),
        "at" to listOf("least", "the", "this", "that", "home", "work"),
        "at least" to listOf("for", "we", "I", "you", "they"),
        "of" to listOf("course", "the", "a", "this", "that", "it"),
        "of course" to listOf("I", "you", "we", "they", "not"),
        "for" to listOf("now", "example", "instance", "sure", "you", "me"),
        "for example" to listOf("you", "we", "if", "the"),
        "for instance" to listOf("you", "we", "if", "the"),
        "for sure" to listOf("I", "you", "we"),
        "just" to listOf("wanted", "let", "saw", "checking", "to", "got"),
        "just wanted" to listOf("to", "say", "let"),
        "nice" to listOf("to", "meeting", "work", "job", "day"),
        "nice to" to listOf("meet", "see", "hear"),
        "nice to meet" to listOf("you"),
        "nice meeting" to listOf("you"),
        "good to" to listOf("see", "hear", "know", "go"),
        "good to see" to listOf("you"),
        "great" to listOf("to", "job", "work", "idea", "day"),
        "great to" to listOf("see", "hear", "know"),
        "want to" to listOf("go", "see", "know", "do", "be"),
        "wanted to" to listOf("say", "let", "tell", "ask"),
        "going to" to listOf("be", "go", "do", "see", "have"),
        "have to" to listOf("go", "do", "see", "be", "get"),
        "i have to" to listOf("go", "do", "see", "be", "get"),
        "i need to" to listOf("go", "do", "see", "get", "know"),
        "i want to" to listOf("go", "see", "know", "do", "be"),
        "i would" to listOf("like", "love", "say", "think"),
        "i would like" to listOf("to", "you"),
        "i would like to" to listOf("go", "see", "know", "do", "be"),
        "i would love" to listOf("to", "you"),
        "would you" to listOf("like", "want", "mind"),
        "would you like" to listOf("to", "me"),
        "do you" to listOf("want", "know", "have", "think", "need"),
        "do you want" to listOf("to", "me"),
        "did you" to listOf("get", "see", "know", "go", "have"),
        "are you" to listOf("going", "okay", "ready", "sure", "free"),
        "is it" to listOf("okay", "ok", "true", "real"),
        "is that" to listOf("okay", "ok", "true", "right", "real")
    )

    /**
     * @param prevWords the context words (lower-cased) before the typed prefix.
     * @param typedPrefix what the user has typed so far for the current word.
     * @param limit max number of completions to return.
     */
    fun suggestPhraseCompletions(
        prevWords: List<String>,
        typedPrefix: String,
        limit: Int = 3
    ): List<String> {
        if (prevWords.isEmpty()) return emptyList()
        val last = prevWords.last()
        // Try a 2-word key first (e.g. "i'll be"), then fall back to 1-word.
        val twoWordKey = if (prevWords.size >= 2) {
            "${prevWords[prevWords.size - 2]} ${prevWords.last()}"
        } else null
        val candidates = ArrayList<String>()
        twoWordKey?.let { bigrams[it]?.let { candidates.addAll(it) } }
        if (candidates.isEmpty()) bigrams[last]?.let { candidates.addAll(it) }
        if (candidates.isEmpty()) return emptyList()
        val filtered = if (typedPrefix.isEmpty()) candidates
        else candidates.filter { it.startsWith(typedPrefix, ignoreCase = true) }
        return filtered.take(limit)
    }
}
