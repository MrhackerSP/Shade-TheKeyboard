package com.noircipher.shade.engine

/**
 * #15 — Smart response composer.
 *
 * Suggests multi-word completion templates for common short responses,
 * e.g. "ok" → "ok sounds good", "thanks" → "thanks I appreciate it".
 *
 * Useful for chat-style fields where the user is replying concisely.
 */
object SmartResponseComposer {

    private val templates: List<Pair<String, List<String>>> = listOf(
        "ok" to listOf("ok sounds good", "ok thanks", "ok great", "ok let me know"),
        "okay" to listOf("okay sounds good", "okay thanks", "okay got it"),
        "k" to listOf("k sounds good", "k thanks", "k got it"),
        "thanks" to listOf("thanks I appreciate it", "thanks so much",
            "thanks for letting me know", "thanks for the heads up"),
        "thx" to listOf("thx a lot", "thx so much"),
        "thank you" to listOf("thank you so much", "thank you very much",
            "thank you for letting me know"),
        "sure" to listOf("sure sounds good", "sure thing", "sure no problem"),
        "hello" to listOf("hello how are you", "hello there", "hello hope you're well"),
        "hi" to listOf("hi how are you", "hi there", "hi how's it going"),
        "hey" to listOf("hey how are you", "hey there", "hey what's up"),
        "yes" to listOf("yes that works", "yes sounds good", "yes please"),
        "no" to listOf("no problem", "no worries", "no thank you"),
        "nope" to listOf("nope no worries", "nope that's fine"),
        "yep" to listOf("yep sounds good", "yep that works"),
        "yeah" to listOf("yeah sounds good", "yeah that works", "yeah sure"),
        "maybe" to listOf("maybe let me check", "maybe later", "maybe tomorrow"),
        "sorry" to listOf("sorry about that", "sorry I missed this",
            "sorry for the delay", "sorry let me check"),
        "will" to listOf("will do", "will get back to you", "will let you know"),
        "let me" to listOf("let me check", "let me know", "let me see"),
        "i'll" to listOf("i'll let you know", "i'll get back to you",
            "i'll check and confirm"),
        "i'm" to listOf("i'm on it", "i'm good thanks", "i'm doing well"),
        "i am" to listOf("i am on it", "i am good thanks"),
        "got" to listOf("got it thanks", "got it will do", "got it sounds good"),
        "got it" to listOf("got it thanks", "got it will do"),
        "gotcha" to listOf("gotcha thanks", "gotcha sounds good"),
        "cool" to listOf("cool sounds good", "cool thanks"),
        "great" to listOf("great thanks", "great sounds good", "great to hear"),
        "awesome" to listOf("awesome thanks", "awesome sounds good"),
        "perfect" to listOf("perfect thanks", "perfect sounds good"),
        "sounds" to listOf("sounds good", "sounds great", "sounds good to me"),
        "sounds good" to listOf("sounds good to me", "sounds good thanks"),
        "talk" to listOf("talk soon", "talk to you later", "talk tomorrow"),
        "see" to listOf("see you soon", "see you tomorrow", "see you later"),
        "see you" to listOf("see you soon", "see you tomorrow", "see you later"),
        "bye" to listOf("bye have a good one", "bye talk soon"),
        "good" to listOf("good to hear", "good thanks", "good let's do it"),
        "good morning" to listOf("good morning hope you're well",
            "good morning how are you"),
        "good evening" to listOf("good evening how are you",
            "good evening hope you're well"),
        "good night" to listOf("good night talk soon", "good night sleep well"),
        "have a" to listOf("have a good one", "have a great day",
            "have a good day"),
        "what's" to listOf("what's up", "what's new", "what's going on"),
        "whats" to listOf("whats up", "whats new", "whats going on"),
        "how" to listOf("how are you", "how's it going", "how have you been"),
        "how are" to listOf("how are you", "how are things"),
        "how's" to listOf("how's it going", "how's everything"),
        "hows" to listOf("hows it going", "hows everything"),
        "morning" to listOf("morning hope you're well", "morning how are you"),
        "evening" to listOf("evening how are you", "evening hope you're well")
    )

    private val index: Map<String, List<String>> = templates.toMap()

    /**
     * @param typed the user's typed word or phrase prefix.
     * @param limit max number of completions to return.
     */
    fun suggest(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val exact = index[lower]
        if (exact != null) return exact.take(limit)
        // Fallback: any template whose key starts with `typed`.
        val matches = templates.filter { (k, _) -> k.startsWith(lower) }
            .flatMap { (_, v) -> v }
        return matches.distinct().take(limit)
    }
}
