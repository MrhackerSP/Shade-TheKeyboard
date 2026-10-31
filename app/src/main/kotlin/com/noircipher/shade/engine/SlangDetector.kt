package com.noircipher.shade.engine

/**
 * Recognises common slang / colloquial tokens that should NOT be autocorrected
 * to a "dictionary" spelling. The list covers informal English used in chat:
 * `gonna`, `wanna`, `cuz`, `tho`, `lol`, etc.
 *
 * When [shouldNotAutocorrect] returns `true`, the autocorrect pipeline should
 * treat the typed token as intentional and skip the dictionary correction step.
 *
 * This is purely a stop-list — it does not suggest slang, it only defends it
 * against being "corrected" away.
 */
object SlangDetector {

    private val SLANG: Set<String> = setOf(
        // Contractions / informal grammar
        "gonna", "wanna", "gotta", "lemme", "gimme", "dunno", "kinda", "sorta",
        "outta", "coulda", "shoulda", "woulda", "musta", "lemme", "gimme",
        "tryna", "fitna", "boutta", "oughta", "whatcha", "gotcha", "betcha",
        // Common informal spellings
        "cuz", "coz", "cause", "tho", "thru", "nite", "lite", "thx", "pls",
        "plz", "ok", "okay", "k", "kk", "ya", "yea", "yeah", "yep", "yup",
        "nope", "nah", "nahh", "yolo", "fomo",
        // Onomatopoeia / laughter
        "lol", "lmao", "lmfao", "rofl", "haha", "hehe", "hoho", "lolz", "kek",
        "xd", "lolol", "hahaha", "bwahaha", "muahaha",
        // Fillers / discourse markers
        "hmm", "mm", "mmm", "huh", "uh", "um", "uhh", "umm", "ohh", "ahh",
        "wow", "whoa", "woah", "oy", "oi", "psst", "ahem", "ugh", "meh", "eh",
        // Internet abbreviations
        "omg", "wtf", "smh", "tbh", "imo", "imho", "fyi", "icymi", "nvm",
        "np", "yw", "rn", "ttyl", "brb", "bbl", "bbs", "afk", "idk", "idc",
        "ikr", "irl", "nsfw", "tba", "tbd", "faq", "aka", "asap", "diy", "fog",
        // Informal shortened forms
        "bro", "bruh", "sis", "bestie", "besty", "babe", "hun", "luv", "ya",
        // Misc
        "yay", "woo", "yippee", "hooray", "hurrah", "woot", "yeehaw"
    )

    /** True when [typed] is a recognised slang token and should not be autocorrected. */
    fun shouldNotAutocorrect(typed: String): Boolean {
        if (typed.isEmpty()) return false
        val lower = typed.lowercase()
        // Allow trailing punctuation like "lol!" or "tho,".
        val stripped = lower.trimEnd { it == '!' || it == '?' || it == '.' || it == ',' || it == ';' }
        return stripped in SLANG
    }

    /** Number of recognised slang tokens (for tests / sanity checks). */
    val size: Int get() = SLANG.size
}
