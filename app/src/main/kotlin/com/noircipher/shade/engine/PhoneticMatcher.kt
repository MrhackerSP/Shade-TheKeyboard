package com.noircipher.shade.engine

/**
 * Soundex-like phonetic matcher.
 *
 * Maps a word to a 4-character code (letter + 3 digits) that is stable across
 * minor spelling variations of the same pronunciation.  Two words with the
 * same code are likely pronounced the same — useful for catching typos that
 * edit-distance alone would miss (e.g. "knight" vs "nite" → both N200-ish).
 *
 * The classic Soundex rules:
 *   1. Keep the first letter as-is, uppercased.
 *   2. Map remaining letters to digits:
 *        b,f,p,v        → 1
 *        c,g,j,k,q,s,x,z → 2
 *        d,t            → 3
 *        l              → 4
 *        m,n            → 5
 *        r              → 6
 *      a,e,i,o,u,y,h,w  → 0 (vowels / fillers, dropped)
 *   3. Collapse adjacent identical digits into one.
 *   4. Drop the 0s.
 *   5. Pad with 0s to length 4 (truncate if longer).
 */
object PhoneticMatcher {

    private val CODE = IntArray(26) { 0 }.also { table ->
        val map = "01230120022455012623010202"
        for (i in 0 until 26) table[i] = map[i] - '0'
    }

    private fun code(ch: Char): Int =
        if (ch in 'a'..'z') CODE[ch - 'a']
        else if (ch in 'A'..'Z') CODE[ch - 'A']
        else -1

    /** Returns the 4-character Soundex code for [word], or "0000" for empty input. */
    fun soundex(word: String): String {
        if (word.isEmpty()) return "0000"
        val lower = word.lowercase()
        val sb = StringBuilder(4)
        sb.append(lower[0].uppercaseChar())
        var prevCode = code(lower[0]).coerceAtLeast(0)
        var written = 1
        var i = 1
        while (i < lower.length && written < 4) {
            val c = lower[i]
            val cd = code(c)
            if (cd < 0) { i++; continue }
            if (cd != 0 && cd != prevCode) {
                sb.append('0' + cd)
                written++
            }
            prevCode = cd
            i++
        }
        while (sb.length < 4) sb.append('0')
        return sb.toString()
    }

    /**
     * Returns true when [a] and [b] are phonetically equivalent (same Soundex
     * code).  Useful for catching typos like "philippe" vs "philip".
     */
    fun soundsLike(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && soundex(a) == soundex(b)

    /**
     * Returns a phonetic similarity score in [0, 1]: 1.0 when the codes match
     * exactly, 0.0 when the first letters differ, 0.5 when the first letter
     * matches but the digit part does not.
     */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val ca = soundex(a)
        val cb = soundex(b)
        if (ca == cb) return 1f
        if (ca[0] != cb[0]) return 0f
        // Same first letter, different digits — partial credit.
        var matches = 0
        for (i in 1 until 4) if (ca[i] == cb[i]) matches++
        return 0.4f + 0.15f * matches
    }
}
