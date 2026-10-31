package com.noircipher.shade.engine

/**
 * #1 — Abbreviation expander.
 *
 * Common chat abbreviations (btw, omw, idk, tbh, asap, brb, ...) get expanded
 * to their full form on the next space, when autocorrect is allowed. Returns
 * null for words that aren't recognized abbreviations so the rest of the
 * autocorrect pipeline can run normally.
 *
 * Capitalization is preserved from the typed word's first character.
 */
object AbbreviationExpander {

    private val table: Map<String, String> = mapOf(
        "btw" to "by the way",
        "omw" to "on my way",
        "idk" to "I don't know",
        "tbh" to "to be honest",
        "asap" to "as soon as possible",
        "brb" to "be right back",
        "ttyl" to "talk to you later",
        "np" to "no problem",
        "yw" to "you're welcome",
        "thx" to "thanks",
        "tx" to "thanks",
        "pls" to "please",
        "plz" to "please",
        "ty" to "thank you",
        "tyvm" to "thank you very much",
        "afk" to "away from keyboard",
        "imo" to "in my opinion",
        "imho" to "in my honest opinion",
        "iirc" to "if I recall correctly",
        "fyi" to "for your information",
        "diy" to "do it yourself",
        "aka" to "also known as",
        "eta" to "estimated time of arrival",
        "faq" to "frequently asked questions",
        "faqed" to "frequently asked questions",
        "tba" to "to be announced",
        "tbd" to "to be determined",
        "tbc" to "to be confirmed",
        "rsVP".lowercase() to "répondez s'il vous plaît",
        "fyi" to "for your information",
        "nvm" to "never mind",
        "ikr" to "I know, right",
        "smh" to "shaking my head",
        "fomo" to "fear of missing out",
        "yolo" to "you only live once",
        "lol" to "laughing out loud",
        "lmao" to "laughing my ass off",
        "rofl" to "rolling on the floor laughing",
        "omg" to "oh my god",
        "wtf" to "what the fuck",
        "stfu" to "shut the fuck up",
        "jk" to "just kidding",
        "ngl" to "not gonna lie",
        "rn" to "right now",
        "fr" to "for real",
        "hbu" to "how about you",
        "wbu" to "what about you",
        "wyd" to "what are you doing",
        "wya" to "where are you",
        "ttfn" to "ta-ta for now",
        "bbl" to "be back later",
        "bbs" to "be back soon",
        "cu" to "see you",
        "cya" to "see you",
        "gtg" to "got to go",
        "g2g" to "got to go",
        "lmk" to "let me know",
        "ic" to "I see",
        "i.e" to "that is",
        "ie" to "that is",
        "e.g" to "for example",
        "eg" to "for example",
        "w/" to "with",
        "w/o" to "without",
        "b/c" to "because",
        "bc" to "because",
        "approx" to "approximately",
        "def" to "definitely",
        "prob" to "probably",
        "obvs" to "obviously",
        "obvi" to "obviously",
        "ppl" to "people",
        "sis" to "sister",
        "bro" to "brother",
        "bab" to "baby",
        "babe" to "baby"
    )

    fun expand(typed: String): String? {
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val expansion = table[lower] ?: return null
        // Preserve capitalization pattern: all-caps stays all-caps,
        // first-cap stays first-cap.
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> expansion.uppercase()
            typed[0].isUpperCase() -> expansion.replaceFirstChar { it.uppercase() }
            else -> expansion
        }
    }
}
