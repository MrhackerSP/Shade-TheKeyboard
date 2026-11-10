package com.noircipher.shade.engine

/**
 * Correction policy: decides when to correct and when to leave a word alone.
 *
 * Two modes:
 * - SAFE: Conservative. Won't correct 2-3 letter words without touch data.
 *   Won't correct if two candidates are nearly tied. Higher keep-cost threshold.
 * - NORMAL: Aggressive. Will correct short words with touch data. Lower threshold.
 *
 * The keep-cost model: every typed word has an implicit "this isn't a typo" cost.
 * If the best correction's score doesn't beat this cost, the word is left as-is.
 * This prevents correcting names, slang, and words the user intentionally typed.
 */
object CorrectionPolicy {

    enum class Mode { SAFE, NORMAL }

    /**
     * Base keep-cost: how expensive it is to say "this isn't a typo."
     * Longer words get a higher keep-cost (more evidence needed to override).
     */
    fun keepCost(len: Int, mode: Mode): Float {
        val base = if (mode == Mode.NORMAL) 9.45f else 7.8f
        val perChar = 0.58f * maxOf(0, len - 4)
        return base + perChar
    }

    /**
     * Should we correct a word of this length in this mode?
     */
    fun shouldAttempt(len: Int, mode: Mode, hasTouchData: Boolean): Boolean {
        if (len < 2) return false
        if (len == 2 && (mode == Mode.SAFE || !hasTouchData)) return false
        if (len == 3 && mode == Mode.SAFE && !hasTouchData) return false
        return true
    }

    /**
     * In SAFE mode, don't correct if two candidates are nearly tied.
     * Returns true if the correction should be suppressed.
     */
    fun isAmbiguous(bestScore: Float, secondScore: Float, mode: Mode): Boolean {
        if (mode != Mode.SAFE) return false
        return secondScore - bestScore < 1.0f
    }

    /**
     * If the typed word IS in the dictionary but rare, only correct if
     * the candidate is significantly better.
     */
    fun shouldCorrectKnownWord(
        rank: Int,
        margin: Float,
        keepMinusBest: Float,
        mode: Mode
    ): Boolean {
        if (rank < 4000) return false  // common word — don't touch
        val requiredMargin = if (mode == Mode.NORMAL) margin else margin + 1.5f
        return keepMinusBest >= requiredMargin
    }
}
