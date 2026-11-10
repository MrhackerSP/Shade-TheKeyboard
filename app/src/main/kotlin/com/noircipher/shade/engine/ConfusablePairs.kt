package com.noircipher.shade.engine

/**
 * #22 — Confusable pairs: there/their/they're, your/you're, its/it's, to/too, then/than, ...
 *
 * Two kinds of rule, because a homophone is decided by different neighbours:
 *
 *  - [prevRules]  look at the word BEFORE the typed one ("more then" -> "more than"). The typed
 *    word is the one being corrected, so these can surface as a suggestion while typing it.
 *  - [nextRules]  look at the word AFTER ("your welcome" -> "you're welcome", "to late" ->
 *    "too late"). The following word doesn't exist yet when the confusable word is typed, so these
 *    are applied one word later through [correctPrevious], which rewrites the previous word.
 *
 * Earlier all rules were treated as previous-word rules, which meant "your/its/to/whose/..." rules
 * never fired where intended and some surfaced wrong suggestions ("want to" -> "too"). Rules that
 * are ambiguous in common legitimate sentences (were/we're, are/our + nouns, "they're friends")
 * were dropped rather than guessed.
 */
object ConfusablePairs {

    private data class PrevRule(
        val typed: String,
        val correction: String,
        /** Correct only when the previous word is one of these. */
        val after: Set<String>
    )

    private data class NextRule(
        val typed: String,
        val correction: String,
        /** Correct only when the word that follows is one of these. */
        val before: Set<String>,
        /** Skip when the word before the typed one is one of these (legitimate phrasings). */
        val unlessPrev: Set<String> = emptySet(),
        /** If non-empty, correct only when the word before the typed one is one of these. */
        val requirePrev: Set<String> = emptySet()
    )

    private fun s(vararg w: String) = w.toSet()

    private val thereNegations = s(
        "is", "are", "was", "were", "be", "been", "any", "no", "if", "whether", "there", "when",
        "where", "why", "how", "what", "isn't", "aren't", "wasn't", "weren't", "to", "will",
        "would", "can", "could", "might", "may", "should", "has", "have", "had", "do", "does", "did"
    )

    private val prevRules: List<PrevRule> = listOf(
        PrevRule("their", "there", s("over", "go", "went", "going", "anywhere", "nowhere", "somewhere", "everywhere", "right")),
        PrevRule("they're", "their", s("with", "from", "by", "of", "about", "for", "to")),
        PrevRule("they're", "there", s("over", "go", "went", "going", "anywhere", "nowhere", "somewhere", "everywhere")),
        PrevRule("then", "than", s("more", "less", "better", "worse", "bigger", "smaller", "older", "younger", "faster", "slower", "greater", "higher", "lower", "larger", "rather", "other")),
        PrevRule("than", "then", s("and", "so", "but", "we", "i", "you", "they", "first")),
        PrevRule("affect", "effect", s("the", "a", "an", "no", "any", "some", "this", "that", "negative", "positive", "side", "good", "bad", "overall", "lasting")),
        PrevRule("effect", "affect", s("will", "would", "can", "could", "may", "might", "did", "didn't", "doesn't", "don't")),
        PrevRule("accept", "except", s("all", "everyone", "everybody", "nobody")),
        PrevRule("except", "accept", s("to", "won't", "didn't", "couldn't", "can't", "refused", "declined")),
        PrevRule("principle", "principal", s("school", "vice", "deputy", "assistant", "loan")),
        PrevRule("where", "were", s("we", "they", "you")),
        PrevRule("threw", "through", s("go", "going", "went", "come", "pass", "walk", "run", "look")),
        PrevRule("weather", "whether", s("decide", "decided", "deciding", "wonder", "wondering", "unsure", "know")),
        PrevRule("loose", "lose", s("will", "won't", "didn't", "don't", "can't", "couldn't", "might", "may", "going", "to")),
        PrevRule("advice", "advise", s("to", "i", "we", "they", "you", "should", "would", "can", "could"))
    )

    private val nextRules: List<NextRule> = listOf(
        NextRule("your", "you're", s("welcome", "going", "gonna", "doing", "being", "coming", "not", "very", "really", "so", "a", "an", "the")),
        NextRule("its", "it's", s("a", "the", "not", "been", "going", "getting", "looking", "ok", "okay", "true", "nice", "fine", "great", "awesome", "ready", "so", "very", "really", "just")),
        NextRule("their", "there", s("is", "are", "was", "were", "isn't", "aren't", "nothing", "something", "no", "anything")),
        NextRule("their", "they're", s("going", "gonna", "not", "always", "being", "coming", "doing")),
        NextRule("there", "their", s("own", "car", "house", "kids", "children", "names", "name", "phone", "dog", "cat", "friends", "parents", "family", "mom", "dad"), unlessPrev = thereNegations),
        NextRule("they're", "their", s("own", "car", "house", "dog", "cat", "phone", "names", "name")),
        NextRule("to", "too", s("late", "early", "much", "many", "few", "soon", "expensive", "cheap", "tired"), unlessPrev = s("up", "back")),
        NextRule("whose", "who's", s("going", "coming", "calling", "talking", "speaking", "there", "here", "next", "that", "ready", "been", "gonna")),
        NextRule("who's", "whose", s("car", "house", "dog", "cat", "phone", "idea", "turn", "fault", "responsibility", "decision", "book", "bag", "name", "side")),
        NextRule("our", "are", s("going", "coming", "ready", "ok", "okay", "gonna", "not"), requirePrev = s("we", "you", "they", "who", "what", "where", "how", "why")),
        NextRule("weather", "whether", s("or"))
    )

    private val confusableWords: Set<String> =
        (prevRules.map { it.typed } + nextRules.map { it.typed }).toSet()

    fun isConfusable(typed: String): Boolean {
        if (typed.isEmpty()) return false
        return confusableWords.contains(typed.lowercase())
    }

    /**
     * Previous-word rules: [typed] is the word being typed, [prevWord] the one before it.
     * Returns the corrected word, or null when no rule applies.
     */
    fun correct(typed: String, prevWord: String?): String? {
        if (typed.isEmpty()) return null
        val prev = prevWord?.trim()?.lowercase() ?: return null
        val lower = typed.lowercase()
        for (rule in prevRules) {
            if (rule.typed == lower && prev in rule.after) return matchCase(typed, rule.correction)
        }
        return null
    }

    /**
     * Next-word rules: [prev] (lower-case) is the word that was just left behind, [next] the word
     * that has just been completed after it, [beforePrev] the word before [prev] (or null).
     * Returns the replacement for [prev] in lower case, or null when no rule applies.
     */
    fun correctPrevious(prev: String, next: String, beforePrev: String?): String? {
        val p = prev.lowercase()
        val n = next.lowercase()
        val bp = beforePrev?.lowercase()
        for (rule in nextRules) {
            if (rule.typed != p || n !in rule.before) continue
            if (bp != null && bp in rule.unlessPrev) continue
            if (rule.requirePrev.isNotEmpty() && (bp == null || bp !in rule.requirePrev)) continue
            return rule.correction
        }
        return null
    }

    fun matchCase(typed: String, replacement: String): String {
        if (typed.isEmpty()) return replacement
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> replacement.uppercase()
            typed[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }
}
