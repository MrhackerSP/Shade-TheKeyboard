package com.noircipher.shade.engine

/**
 * #21 — Sentence completer.
 *
 * Suggests multi-word sentence completions when the user has typed a
 * recognizable sentence-starter. For example:
 *   "how are you" → "doing today?" / "doing?"
 *   "i'll be there" → "in 20 minutes" / "soon"
 *
 * Completions are templates, not literal commits — callers should treat
 * them as one-tap inserts that replace the trailing word(s) the user
 * already typed.
 */
object SentenceCompleter {

    private data class Template(
        val trigger: List<String>,
        val completions: List<String>
    )

    private val templates: List<Template> = listOf(
        Template(listOf("how", "are", "you"), listOf(
            "doing today?", "doing?", "doing", "feeling today?", "been?"
        )),
        Template(listOf("how", "have", "you"), listOf(
            "been?", "been doing?"
        )),
        Template(listOf("how", "do", "you"), listOf(
            "do?", "feel about that?"
        )),
        Template(listOf("how's", "it", "going"), listOf(
            "?", "with you?"
        )),
        Template(listOf("what", "are", "you"), listOf(
            "doing?", "up to?", "doing today?", "doing tomorrow?"
        )),
        Template(listOf("what", "do", "you"), listOf(
            "think?", "mean?", "need?", "want?"
        )),
        Template(listOf("what", "time"), listOf(
            "works for you?", "should we meet?", "is good?"
        )),
        Template(listOf("i'll", "be", "there"), listOf(
            "in 20 minutes", "in a few minutes", "soon", "shortly",
            "in about 10 minutes", "as soon as I can"
        )),
        Template(listOf("i'm", "on", "my"), listOf(
            "way", "way now", "way there"
        )),
        Template(listOf("let", "me", "know"), listOf(
            "if you need anything", "what you think", "when you're ready",
            "if that works", "what works"
        )),
        Template(listOf("let", "me", "check"), listOf(
            "and get back to you", "and confirm", "and let you know"
        )),
        Template(listOf("i", "hope"), listOf(
            "you're well", "you're doing well", "this helps", "that works"
        )),
        Template(listOf("hope"), listOf(
            "you're well", "you're doing well", "this helps", "that works",
            "you had a good weekend"
        )),
        Template(listOf("sorry"), listOf(
            "for the delay", "I missed this", "about that", "for the inconvenience"
        )),
        Template(listOf("thanks"), listOf(
            "for letting me know", "for the heads up", "for your help",
            "for getting back to me", "for the update", "in advance"
        )),
        Template(listOf("thank", "you"), listOf(
            "so much", "for your help", "for letting me know",
            "for getting back to me", "for the update"
        )),
        Template(listOf("just", "wanted", "to"), listOf(
            "let you know", "check in", "follow up", "confirm", "say hi"
        )),
        Template(listOf("just", "checking"), listOf(
            "in", "to see how you're doing", "on this", "if you got this"
        )),
        Template(listOf("feel", "free", "to"), listOf(
            "reach out", "let me know", "ask", "reply", "call me"
        )),
        Template(listOf("please", "let", "me"), listOf(
            "know", "know if you have questions", "know what works"
        )),
        Template(listOf("looking", "forward", "to"), listOf(
            "hearing from you", "seeing you", "meeting you", "talking to you"
        )),
        Template(listOf("talk", "to", "you"), listOf(
            "soon", "later", "tomorrow"
        )),
        Template(listOf("see", "you"), listOf(
            "soon", "later", "tomorrow", "then"
        )),
        Template(listOf("have", "a", "good"), listOf(
            "one", "day", "weekend", "week", "evening"
        )),
        Template(listOf("have", "a", "great"), listOf(
            "day", "weekend", "week"
        )),
        Template(listOf("good", "luck"), listOf(
            "with everything", "today", "tomorrow", "with that"
        )),
        Template(listOf("congratulations"), listOf(
            "on the new job", "on the new role", "on the launch",
            "on your engagement", "on your wedding"
        ))
    )

    /**
     * @param contextWords lower-cased context words before the cursor.
     * @param limit max number of completions.
     * @return matching multi-word completions, or empty.
     */
    fun suggest(contextWords: List<String>, limit: Int = 3): List<String> {
        if (contextWords.isEmpty()) return emptyList()
        val tail = contextWords.takeLast(4)
        val matches = ArrayList<String>()
        for (t in templates) {
            if (matches.size >= limit) break
            if (endsWith(tail, t.trigger)) {
                for (c in t.completions) {
                    if (matches.size >= limit) break
                    matches.add(c)
                }
            }
        }
        return matches
    }

    private fun endsWith(context: List<String>, trigger: List<String>): Boolean {
        if (context.size < trigger.size) return false
        val start = context.size - trigger.size
        for (i in trigger.indices) {
            if (!context[start + i].equals(trigger[i], ignoreCase = true)) return false
        }
        return true
    }
}
