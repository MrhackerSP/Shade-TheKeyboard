package com.noircipher.shade.engine

/**
 * Expands common text-message abbreviations to their full forms once the user
 * commits the abbreviation with a space or punctuation.
 *
 * Examples: `btw` → `by the way`, `omw` → `on my way`, `brb` → `be right back`.
 *
 * Returns `null` when the typed token isn't a recognised abbreviation, so the
 * caller can fall through to the regular dictionary / autocorrect path.
 *
 * The expansion list deliberately favours informal conversation: it is invoked
 * only when the user actually commits the abbreviation (presses space), so it
 * does not interfere with mid-word suggestion.
 */
object AbbreviationExpander {

    private val TABLE: Map<String, String> = mapOf(
        "btw" to "by the way",
        "omw" to "on my way",
        "brb" to "be right back",
        "idk" to "I don't know",
        "imo" to "in my opinion",
        "imho" to "in my humble opinion",
        "tbh" to "to be honest",
        "fyi" to "for your information",
        "asap" to "as soon as possible",
        "atm" to "at the moment",
        "bff" to "best friends forever",
        "bbs" to "be back soon",
        "bbl" to "be back later",
        "cu" to "see you",
        "cul8r" to "see you later",
        "cya" to "see you",
        "dm" to "direct message",
        "eta" to "estimated time of arrival",
        "fomo" to "fear of missing out",
        "ftw" to "for the win",
        "gg" to "good game",
        "gn" to "good night",
        "gr8" to "great",
        "hbu" to "how about you",
        "ily" to "I love you",
        "im" to "I'm",
        "irl" to "in real life",
        "jk" to "just kidding",
        "lmao" to "laughing my ass off",
        "lmk" to "let me know",
        "lol" to "laughing out loud",
        "nvm" to "never mind",
        "np" to "no problem",
        "nsfw" to "not safe for work",
        "nbd" to "no big deal",
        "ofc" to "of course",
        "ok" to "okay",
        "omg" to "oh my god",
        "ppl" to "people",
        "pls" to "please",
        "plz" to "please",
        "pov" to "point of view",
        "rofl" to "rolling on the floor laughing",
        "smh" to "shaking my head",
        "tba" to "to be announced",
        "tbd" to "to be determined",
        "thx" to "thanks",
        "ttyl" to "talk to you later",
        "tia" to "thanks in advance",
        "tmi" to "too much information",
        "ttyl" to "talk to you later",
        "u" to "you",
        "ur" to "your",
        "wbu" to "what about you",
        "wth" to "what the hell",
        "wtf" to "what the fuck",
        "yw" to "you're welcome",
        "yolo" to "you only live once",
        "afaik" to "as far as I know",
        "aka" to "also known as",
        "rn" to "right now",
        "smh" to "shaking my head",
        "smol" to "small",
        "tmi" to "too much information",
        "wyd" to "what are you doing",
        "wym" to "what do you mean",
        "wys" to "what you saying",
        "wya" to "where you at",
        "hmu" to "hit me up",
        "icymi" to "in case you missed it",
        "tbqh" to "to be quite honest",
        "tldr" to "too long; didn't read",
        "w" to "with",
        "wo" to "without",
        "b4" to "before",
        "l8" to "late",
        "l8r" to "later",
        "2day" to "today",
        "2morrow" to "tomorrow",
        "2nite" to "tonight"
    )

    /** Returns the expansion, or null if the token is not a known abbreviation. */
    fun expand(typed: String): String? {
        if (typed.length < 1 || typed.length > 12) return null
        val lower = typed.lowercase()
        val expansion = TABLE[lower] ?: return null
        // Preserve the capitalisation style of the typed token.
        return when {
            typed.all { it.isUpperCase() } && typed.length > 1 -> expansion.uppercase()
            typed.first().isUpperCase() -> expansion.replaceFirstChar { it.uppercase() }
            else -> expansion
        }
    }
}
