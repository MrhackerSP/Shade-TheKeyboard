package com.noircipher.shade.engine

/**
 * #22 — Confusable pairs.
 *
 * Catches classic English homophone confusions: there/their/they're,
 * your/you're, its/it's, to/too/two, then/than, affect/effect, etc.
 *
 * The decision to "correct" depends on the preceding word (context-sensitive).
 * Returns the corrected word, or null when no correction applies.
 */
object ConfusablePairs {

    private data class Rule(
        val typed: String,
        val correction: String,
        /** Optional context-gate: only correct when previous word matches one of these. */
        val contextTrigger: Set<String> = emptySet(),
        /** Avoid correcting when previous word is one of these. */
        val contextBlock: Set<String> = emptySet()
    )

    private val rules: List<Rule> = listOf(
        // there / their / they're
        Rule("their", "there", contextTrigger = setOf("over", "is", "are", "be", "been", "go", "went", "here", "right", "anywhere", "nowhere", "somewhere", "everywhere")),
        Rule("there", "their", contextTrigger = setOf("with", "and", "to", "for", "from", "by", "of", "about", "family", "house", "car", "dog", "cat", "names", "kids", "children", "parents", "friends")),
        Rule("they're", "their", contextTrigger = setOf("with", "and", "to", "for", "from", "by", "of", "about")),
        Rule("they're", "there", contextTrigger = setOf("over", "is", "are", "go", "be", "here", "right", "anywhere")),

        // your / you're
        Rule("your", "you're", contextTrigger = setOf("going", "welcome", "right", "the", "best", "here", "there", "invited", "awesome", "amazing", "great", "ready", "doing", "so")),
        Rule("youre", "you're"),
        Rule("youre", "your", contextBlock = setOf("going", "welcome", "right")),

        // its / it's
        Rule("its", "it's", contextTrigger = setOf("going", "been", "not", "so", "a", "the", "going", "ok", "okay", "true", "been", "getting", "looking", "nice", "fine", "great", "awesome", "ready")),

        // to / too / two
        Rule("to", "too", contextTrigger = setOf("want", "wanted", "needs", "need", "got", "have", "had", "late", "early", "good", "bad", "tired", "much", "many", "few", "easy", "hard", "expensive", "cheap")),

        // then / than
        Rule("then", "than", contextTrigger = setOf("more", "less", "better", "worse", "bigger", "smaller", "older", "younger", "faster", "slower", "rather", "other")),

        // affect / effect
        Rule("affect", "effect", contextTrigger = setOf("the", "a", "an", "no", "any", "some", "this", "that", "negative", "positive", "side", "good", "bad", "overall", "long-term", "short-term", "lasting")),
        Rule("effect", "affect", contextTrigger = setOf("will", "would", "can", "could", "may", "might", "to", "going", "going to", "did", "didn't", "doesn't", "don't")),

        // accept / except
        Rule("accept", "except", contextTrigger = setOf("all", "everyone", "everybody", "nobody", "no-one", "the", "that", "this")),
        Rule("except", "accept", contextTrigger = setOf("to", "won't", "won't", "didn't", "couldn't", "can't", "refused", "declined")),

        // principle / principal
        Rule("principle", "principal", contextTrigger = setOf("the", "school", "company", "investor", "investors", "loan")),

        // whose / who's
        Rule("whose", "who's", contextTrigger = setOf("going", "coming", "here", "there", "calling", "talking", "speaking")),
        Rule("who's", "whose", contextTrigger = setOf("car", "house", "dog", "cat", "phone", "idea", "turn", "responsibility", "fault", "decision")),

        // were / where / we're
        Rule("were", "we're", contextTrigger = setOf("going", "coming", "here", "there", "going to", "about to", "trying")),
        Rule("were", "where", contextTrigger = setOf("the", "a", "place", "location", "house", "school", "work", "home", "exactly")),
        Rule("where", "were", contextTrigger = setOf("we", "they", "you", "kids", "children", "parents", "guys")),

        // through / threw / thorough
        Rule("threw", "through", contextTrigger = setOf("go", "going", "went", "come", "pass", "walk", "run", "look")),

        // are / our
        Rule("are", "our", contextTrigger = setOf("friends", "kids", "parents", "family", "team", "house", "car", "meeting", "schedule", "plans", "trip", "vacation")),
        Rule("our", "are", contextTrigger = setOf("going", "coming", "here", "there", "ready", "ok", "okay", "fine", "good")),

        // weather / whether
        Rule("weather", "whether", contextTrigger = setOf("to", "or", "not", "depends", "decide", "decided", "know", "wonder", "depends", "on")),

        // lose / loose
        Rule("loose", "lose", contextTrigger = setOf("will", "won't", "didn't", "don't", "can't", "couldn't", "might", "may", "going", "to")),

        // advice / advise
        Rule("advice", "advise", contextTrigger = setOf("to", "i", "we", "they", "you", "should", "would", "can", "could")),

        // then / than reverse
        Rule("than", "then", contextTrigger = setOf("and", "so", "but", "we", "i", "you", "they", "first", "next", "after", "before", "now", "later"))
    )

    private val confusableWords: Set<String> = rules.map { it.typed }.toSet()

    fun isConfusable(typed: String): Boolean {
        if (typed.isEmpty()) return false
        return confusableWords.contains(typed.lowercase())
    }

    /**
     * @param typed the user's word
     * @param prevWord the previous word in the sentence (or null at start)
     * @return corrected word, or null when no rule applies.
     */
    fun correct(typed: String, prevWord: String?): String? {
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val prev = prevWord?.trim()?.lowercase()
        for (rule in rules) {
            if (rule.typed != lower) continue
            if (prev != null) {
                if (rule.contextBlock.contains(prev)) continue
                if (rule.contextTrigger.isNotEmpty() && !rule.contextTrigger.contains(prev)) continue
            } else {
                // No context — only apply rules that don't require context.
                if (rule.contextTrigger.isNotEmpty()) continue
            }
            return preserveCase(typed, rule.correction)
        }
        return null
    }

    private fun preserveCase(typed: String, replacement: String): String {
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> replacement.uppercase()
            typed[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }
}
