package com.noircipher.shade.engine

/**
 * #2 — Slang detector.
 *
 * Common informal words (gonna, wanna, lol, etc.) should NOT be "autocorrected"
 * to formal English — they're intentional slang. Returning true for one of
 * these words tells the autocorrect pipeline to leave the user's typing alone.
 */
object SlangDetector {

    private val slang = HashSet(
        listOf(
            // Contractions-in-progress / informal contractions
            "gonna", "wanna", "gotta", "kinda", "sorta", "lemme", "gimme",
            "outta", "coulda", "woulda", "shoulda", "ain't", "y'all", "cuz",
            "coz", "cus", "cause", "tho", "tho'", "ya", "yea", "yeah", "yep",
            "nope", "nah", "nope", "uhhuh", "uhh", "uh", "hmm", "hmmm", "mmm",
            "k", "kk", "ok", "okay", "okie", "okiez", "yo", "yooo",
            // Internet slang / abbreviations
            "lol", "lmao", "lmfao", "rofl", "omg", "omfg", "wtf", "stfu",
            "afk", "brb", "bbl", "bbs", "ttyl", "ttfn", "cya", "cu",
            "tbh", "imo", "imho", "iykyk", "fomo", "yolo", "smh", "ikr",
            "rn", "nvm", "ikr", "ngl", "tbvh", "jk", "jkw", "btw",
            "rn", "fr", "frfr", "hbu", "hby", "wbu", "wby", "wyd",
            "wya", "y?", "n?", "u", "ur", "ya", "ya'll",
            // Filler / interjections
            "ugh", "meh", "bleh", "ughh", "ughhh", "eww", "ew", "ewww",
            "haha", "hehe", "hihi", "hoho", "mwahaha", "hahaha",
            "woot", "yay", "woohoo", "woo", "wee",
            "duh", "obvs", "totes", "obvi", "adorbs", "amaze",
            "kewl", "k", "kk", "okk", "okiee", "okiezz",
            "sux", "suck", "sucks", "fail", "epic", "win",
            // Caps-emphasis variants we shouldn't fight
            "ok", "okay", "yeah", "yep", "nope", "wow", "hmm",
            // Common Hinglish / Romanized slang that often trips up dictionaries
            "acha", "achha", "accha", "theek", "thik", "haan", "nahi", "nahin",
            "bhai", "yaar", "arre", "arey", "kya", "kyu", "kyun", "matlab"
        )
    )

    fun shouldNotAutocorrect(word: String): Boolean {
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        if (slang.contains(lower)) return true
        // Repeated-letter emphasis: "soooo", "nooo", "ahhh", "lollll" — leave alone.
        if (lower.length >= 4) {
            val distinct = lower.toSet()
            if (distinct.size <= 3) {
                val last = lower.last()
                if (lower.count { it == last } >= 3) return true
            }
        }
        return false
    }
}
