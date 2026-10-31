package com.noircipher.shade.engine

/**
 * Corrects common "smushed" multi-word phrases back to their properly-spaced
 * forms.
 *
 * Examples:
 *   "alot"    → "a lot"
 *   "aswell"  → "as well"
 *   "inspite" → "in spite"
 *   "alittle" → "a little"
 *   "alright" → "all right" (debatable but matches the policy here)
 *
 * Returns `null` when [typed] is not a known smushed form.
 */
object MultiWordCorrector {

    private val TABLE: Map<String, String> = mapOf(
        "alot" to "a lot",
        "aswell" to "as well",
        "inspite" to "in spite",
        "alittle" to "a little",
        "awhile" to "a while",
        "alright" to "all right",
        "infact" to "in fact",
        "incase" to "in case",
        "ofcourse" to "of course",
        "allmost" to "almost",
        "asif" to "as if",
        "asfar" to "as far",
        "aslong" to "as long",
        "aswellas" to "as well as",
        "infront" to "in front",
        "inorder" to "in order",
        "intouch" to "in touch",
        "insteadof" to "instead of",
        "outof" to "out of",
        "upto" to "up to",
        "thankyou" to "thank you",
        "goodbye" to "good bye",
        "goodnight" to "good night",
        "goodmorning" to "good morning",
        "goodafternoon" to "good afternoon",
        "goodevening" to "good evening",
        "bestregards" to "best regards",
        "kindregards" to "kind regards",
        "withregards" to "with regards",
        "forexample" to "for example",
        "bythe way" to "by the way",
        "bytheway" to "by the way",
        "inotherwords" to "in other words",
        "ontheotherhand" to "on the other hand",
        "atleast" to "at least",
        "atmost" to "at most",
        "onceagain" to "once again",
        "sofar" to "so far",
        "sothat" to "so that",
        "suchas" to "such as",
        "evenif" to "even if",
        "eventhough" to "even though",
        "asmuchas" to "as much as",
        "aswell" to "as well",
        "nomatter" to "no matter",
        "letme" to "let me",
        "letus" to "let us",
        "tobe" to "to be",
        "totry" to "to try",
        "goingto" to "going to",
        "haveto" to "have to",
        "wantto" to "want to",
        "needto" to "need to",
        "supposedto" to "supposed to",
        "usedto" to "used to",
        "kindof" to "kind of",
        "sortof" to "sort of",
        "outthere" to "out there",
        "overhere" to "over here",
        "overthere" to "over there",
        "rightnow" to "right now",
        "justnow" to "just now",
        "lateron" to "later on"
    )

    /** Returns the corrected multi-word form, or null when not recognised. */
    fun correct(typed: String): String? {
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val match = TABLE[lower] ?: return null
        return when {
            typed.all { it.isUpperCase() } && typed.length > 1 -> match.uppercase()
            typed.first().isUpperCase() -> match.replaceFirstChar { it.uppercase() }
            else -> match
        }
    }
}
