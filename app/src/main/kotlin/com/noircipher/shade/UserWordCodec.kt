package com.noircipher.shade

/**
 * Pure-Kotlin (no Android imports, so it is unit-testable on the JVM) text
 * format for the learned-word stores: one `word<TAB>score` pair per line.
 *
 * Words only ever contain letters, apostrophes and combining marks, so TAB and
 * newline can never appear inside a word and need no escaping.
 */
object UserWordCodec {

    const val MIN_WORD_LEN = 2
    const val MAX_WORD_LEN = 32

    /** Letters, apostrophes and combining marks (needed for Hindi vowel signs). */
    fun isWordChar(c: Char): Boolean {
        if (c == '\'' || c.isLetter()) return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun isValidWord(w: String): Boolean =
        w.length in MIN_WORD_LEN..MAX_WORD_LEN && w.all { isWordChar(it) }

    fun encode(map: Map<String, Float>): String {
        val sb = StringBuilder(map.size * 12)
        for ((w, s) in map) {
            if (!isValidWord(w) || !s.isFinite()) continue
            sb.append(w).append('\t').append(s).append('\n')
        }
        return sb.toString()
    }

    fun decode(text: String): MutableMap<String, Float> {
        val out = HashMap<String, Float>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val w = line.substring(0, tab)
            val s = line.substring(tab + 1).trim().toFloatOrNull() ?: continue
            if (!isValidWord(w) || !s.isFinite()) continue
            out[w] = s
        }
        return out
    }

    /**
     * Converts a legacy SharedPreferences dump (word-or-ciphertext key -> number)
     * into a clean word map.
     *
     * The old store wrote AES-GCM ciphertext (random IV) as the *key*, so one word
     * could appear under many different keys. [decodeKey] turns a stored key into a
     * word (or null if it can't); duplicates collapse to the highest score. Keys that
     * don't decode to a valid word (e.g. ciphertext from another device) are dropped.
     */
    fun fromLegacyEntries(
        entries: Map<String, *>,
        positiveOnly: Boolean = true,
        decodeKey: (String) -> String?
    ): MutableMap<String, Float> {
        val out = HashMap<String, Float>()
        for ((k, v) in entries) {
            val score = when (v) {
                is Float -> v
                is Int -> v.toFloat()
                is Long -> v.toFloat()
                is Double -> v.toFloat()
                else -> continue
            }
            if (!score.isFinite() || (positiveOnly && score <= 0f)) continue
            val word = (decodeKey(k) ?: continue).lowercase()
            if (!isValidWord(word)) continue
            val prev = out[word]
            if (prev == null || score > prev) out[word] = score
        }
        return out
    }

    /** Keeps only valid words with finite scores (used on untrusted backup input). */
    fun sanitize(map: Map<String, Float>): MutableMap<String, Float> {
        val out = HashMap<String, Float>()
        for ((w, s) in map) {
            val lw = w.lowercase()
            if (isValidWord(lw) && s.isFinite()) out[lw] = maxOf(out[lw] ?: s, s)
        }
        return out
    }
}
