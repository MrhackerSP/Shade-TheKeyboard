package com.noircipher.shade.engine

/**
 * #4 — Multi-word corrector.
 *
 * Catches common "missing space" mistakes: "alot" → "a lot",
 * "aswell" → "as well", "inspite" → "in spite", etc.
 *
 * Returns the corrected multi-word phrase, or null when the typed string
 * isn't a known error.
 */
object MultiWordCorrector {

    private val table: Map<String, String> = mapOf(
        "alot" to "a lot",
        "aswell" to "as well",
        "inspite" to "in spite",
        "incase" to "in case",
        "infact" to "in fact",
        "inorder" to "in order",
        "infront" to "in front",
        "atleast" to "at least",
        "atmost" to "at most",
        "eachother" to "each other",
        "ofcourse" to "of course",
        "infact" to "in fact",
        "asif" to "as if",
        "aslong" to "as long",
        "asmuch" to "as much",
        "aswell" to "as well",
        "asif" to "as if",
        "sofar" to "so far",
        "sofar" to "so far",
        "overthere" to "over there",
        "overhere" to "over here",
        "allday" to "all day",
        "allnight" to "all night",
        "allright" to "all right",
        "alittle" to "a little",
        "afew" to "a few",
        "theother" to "the other",
        "nomatter" to "no matter",
        "noway" to "no way",
        "noproblem" to "no problem",
        "nonsense" to "no sense",
        "noworries" to "no worries",
        "forgood" to "for good",
        "forsure" to "for sure",
        "forfree" to "for free",
        "forexample" to "for example",
        "forinstance" to "for instance",
        "bythe" to "by the",
        "bythe way" to "by the way",
        "inthe" to "in the",
        "onthe" to "on the",
        "atthe" to "at the",
        "tothe" to "to the",
        "ofthe" to "of the",
        "andthe" to "and the",
        "butthe" to "but the",
        "forte" to "for the",
        "withthe" to "with the",
        "fromthe" to "from the",
        "andthen" to "and then",
        "butthen" to "but then",
        "itwould" to "it would",
        "iwill" to "I will",
        "i'mgoing" to "I'm going",
        "iam" to "I am",
        "ive" to "I've",
        "im" to "I'm",
        "ill" to "I'll",
        "id" to "I'd",
        "youre" to "you're",
        "theyre" to "they're",
        "weve" to "we've",
        "theyve" to "they've",
        "theyll" to "they'll",
        "wont" to "won't",
        "cant" to "can't",
        "dont" to "don't",
        "doesnt" to "doesn't",
        "didnt" to "didn't",
        "shouldnt" to "shouldn't",
        "wouldnt" to "wouldn't",
        "couldnt" to "couldn't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't"
    )

    /**
     * @param typed the user's typed word
     * @param isDictionaryWord whether the typed word is in the dictionary. If
     *   it is, we generally shouldn't suggest a "correction" because the user
     *   may have meant the literal word.
     */
    fun correct(typed: String, isDictionaryWord: Boolean = false): String? {
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val fix = table[lower] ?: return null
        // Don't suggest a fix for words the dictionary already knows about
        // unless they look like a contraction ("dont" -> "don't"). Those
        // apostrophe-style contractions are always worth suggesting.
        if (isDictionaryWord && !fix.contains('\'')) return null
        // Preserve capitalization.
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> fix.uppercase()
            typed[0].isUpperCase() -> fix.replaceFirstChar { it.uppercase() }
            else -> fix
        }
    }
}
