package com.noircipher.shade

/**
 * Decides how suggestion words are capitalised so they follow the keyboard state.
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
object SuggestionCasing {

    enum class Style { NONE, TITLE, UPPER }

    /**
     * @param lettersMode  false on the symbol / number pads, where shift is irrelevant
     * @param shiftState   0 = off, 1 = one-shot Shift (manual or auto-caps), 2 = Caps Lock
     * @param typedWord    the word currently being typed ("" when starting a new word)
     */
    fun styleFor(lettersMode: Boolean, shiftState: Int, typedWord: String): Style {
        if (!lettersMode) return Style.NONE
        if (shiftState == 2) return Style.UPPER
        val letters = typedWord.filter { it.isLetter() }
        // User is typing in capitals ("OK", "NASA"): keep suggestions in capitals.
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return Style.UPPER
        if (letters.isNotEmpty()) {
            // Shift is consumed by the first letter, so look at the typed word instead.
            return if (letters.first().isUpperCase()) Style.TITLE else Style.NONE
        }
        // Nothing typed yet: Shift armed (manually or by auto-caps after ". ") -> sentence case.
        return if (shiftState == 1) Style.TITLE else Style.NONE
    }

    /**
     * @param protectedText text that must never be re-cased (the clipboard chip), or null
     */
    fun apply(word: String, style: Style, protectedText: String? = null): String {
        if (style == Style.NONE || word.isEmpty()) return word
        // Emoji, punctuation and numbers are never re-cased.
        if (!word[0].isLetter()) return word
        if (protectedText != null && word == protectedText) return word
        return when (style) {
            Style.UPPER -> word.uppercase()
            Style.TITLE -> word.replaceFirstChar { it.titlecase() }
            Style.NONE -> word
        }
    }
}
