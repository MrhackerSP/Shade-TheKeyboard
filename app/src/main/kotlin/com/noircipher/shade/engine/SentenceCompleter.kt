package com.noircipher.shade.engine

/**
 * Multi-token sentence completion templates triggered by a partial phrase.
 *
 * Examples:
 *   "How are you"            → "doing today?"
 *   "I'll be there"          → "in 20 minutes"
 *   "Let me know"            → "if you need anything"
 *   "Talk to you"            → "soon"
 *
 * The matcher is a simple case-insensitive suffix check: the candidate is
 * returned when [typed] (lowercased, trimmed) ends with the trigger phrase.
 */
object SentenceCompleter {

    private data class Template(val trigger: String, val completion: String)

    private val TEMPLATES: List<Template> = listOf(
        Template("how are you", "doing today?"),
        Template("how are you doing", "today?"),
        Template("how have you been", "lately?"),
        Template("what's up", "with you?"),
        Template("what are you doing", "this weekend?"),
        Template("i'll be there", "in 20 minutes"),
        Template("i'll be there", "soon"),
        Template("i'll be right", "back"),
        Template("i'm on my", "way"),
        Template("i'm looking forward", "to it"),
        Template("i'm sorry", "to hear that"),
        Template("i'm sorry", "about that"),
        Template("let me know", "if you need anything"),
        Template("let me know", "what you think"),
        Template("let me know", "when you're free"),
        Template("let me know", "if that works"),
        Template("feel free", "to reach out"),
        Template("feel free", "to ask"),
        Template("feel free", "to let me know"),
        Template("talk to you", "soon"),
        Template("talk to you", "later"),
        Template("get back", "to me"),
        Template("get back", "to you"),
        Template("can we", "reschedule?"),
        Template("can we", "meet up?"),
        Template("can you", "send me the details?"),
        Template("could you", "let me know?"),
        Template("would you", "mind helping?"),
        Template("would you", "like to join?"),
        Template("do you", "want to grab coffee?"),
        Template("do you", "have a minute?"),
        Template("do you", "have time?"),
        Template("i hope", "you're doing well"),
        Template("i hope", "to see you soon"),
        Template("hope you're", "doing well"),
        Template("hope to", "see you soon"),
        Template("have a great", "day"),
        Template("have a great", "weekend"),
        Template("have a good", "one"),
        Template("have a nice", "day"),
        Template("looking forward", "to seeing you"),
        Template("looking forward", "to hearing from you"),
        Template("looking forward", "to it"),
        Template("just checking", "in"),
        Template("just wanted", "to check in"),
        Template("just wanted", "to let you know"),
        Template("just wanted", "to say"),
        Template("just a quick", "reminder"),
        Template("just a quick", "heads up"),
        Template("thank you", "so much"),
        Template("thank you", "for your help"),
        Template("thank you", "for reaching out"),
        Template("thanks for", "letting me know"),
        Template("thanks for", "the update"),
        Template("thanks for", "getting back to me"),
        Template("i appreciate", "your help"),
        Template("i appreciate", "it"),
        Template("glad to", "hear that"),
        Template("glad to", "see you"),
        Template("nice to", "meet you"),
        Template("nice to", "see you"),
        Template("it was nice", "talking to you"),
        Template("it was great", "meeting you"),
        Template("see you", "soon"),
        Template("see you", "later"),
        Template("see you", "tomorrow")
    )

    /**
     * Returns the completion for [typed], or null when no template matches.
     * The match is a case-insensitive exact-suffix check on the trimmed input.
     */
    fun complete(typed: String): String? {
        if (typed.length < 3) return null
        val lower = typed.lowercase().trim()
        for (t in TEMPLATES) {
            if (lower.endsWith(t.trigger)) return t.completion
        }
        return null
    }
}
