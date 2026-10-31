package com.noircipher.shade.engine

/**
 * #23 — Phonetic matcher.
 *
 * Standard Soundex algorithm + a "phonetically similar" predicate, used
 * to find dictionary words that sound like the user's typo. Useful for
 * misspellings driven by pronunciation rather than keyboard layout (e.g.
 * "definitely" → "defiantly" → "definately").
 */
object PhoneticMatcher {

    fun soundex(word: String): String {
        if (word.isEmpty()) return ""
        val s = word.lowercase().filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""
        val result = StringBuilder(4)
        result.append(s[0].uppercaseChar())
        var prevCode = codeFor(s[0])
        var i = 1
        while (i < s.length && result.length < 4) {
            val c = s[i]
            val code = codeFor(c)
            if (code != '0' && code != prevCode) {
                result.append(code)
            }
            if (code != '0') prevCode = code
            i++
        }
        while (result.length < 4) result.append('0')
        return result.toString()
    }

    private fun codeFor(c: Char): Char = when (c) {
        'b', 'f', 'p', 'v' -> '1'
        'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
        'd', 't' -> '3'
        'l' -> '4'
        'm', 'n' -> '5'
        'r' -> '6'
        else -> '0'
    }

    fun phoneticallySimilar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        return soundex(a) == soundex(b)
    }

    fun findPhoneticMatches(
        typed: String,
        dictionary: List<String>,
        limit: Int = 5
    ): List<String> {
        if (typed.isEmpty() || dictionary.isEmpty()) return emptyList()
        val target = soundex(typed)
        if (target.isEmpty()) return emptyList()
        // Prefer words whose first letter matches (soundex retains the first
        // letter so this is consistent with the algorithm).
        val matches = ArrayList<Pair<String, Int>>()
        for (w in dictionary) {
            if (w.length < 2) continue
            val code = soundex(w)
            if (code == target) {
                // Rank by length-similarity to typed.
                val dist = kotlin.math.abs(w.length - typed.length)
                matches.add(w to dist)
            }
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortBy { it.second }
        return matches.take(limit).map { it.first }
    }
}
