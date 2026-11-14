package com.noircipher.shade.engine

import kotlin.math.min

/**
 * Standalone autocorrect confidence, computable for ANY correction candidate
 * without going through the suggestion-ranking pool.
 *
 * Why this exists: the old gate read the candidate's raw ranker score and
 * defaulted to 0 when the candidate had never surfaced in the suggestion bar.
 * calibrated(0) sits around 0.4-0.5, below the 0.85 gate, which silently
 * blocked every autocorrect whose fix was not already a visible suggestion —
 * i.e. the majority of real typo corrections. This scorer gives the
 * correction path its own evidence-based confidence instead of a broken
 * default.
 *
 * Evidence blend (all pure Kotlin, JVM-testable):
 *  - Frequency prior of the candidate (common words are safer fixes)
 *  - Weighted edit cost normalized per character
 *  - Ambiguity margin: gap between the best and second-best candidate
 *  - Touch-geometry evidence: how well the fix matches where fingers landed
 *  - Length caution (short words need more evidence) and known-word caution
 *
 * Returns a probability in [0, 1] calibrated so the classic typos
 * ("teh" -> "the", "definately" -> "definitely") score >= 0.85 while
 * ambiguous or rare-word rewrites score well below the gate.
 */
object CorrectionConfidence {

    /** A correction candidate that was never in the suggestion pool still needs SOME evidence. */
    const val NEURAL_FALLBACK = 0.55f
    const val SYSTEM_SPELL_FALLBACK = 0.65f

    /** Floor for the standalone score of a dictionary-path correction. */
    const val FLOOR = 0f
    const val CEIL = 1f

    /**
     * @param editCost      weighted edit cost from the dictionary matcher
     * @param wordLen       length of the word as typed
     * @param rank          dictionary rank of the candidate (0 = most common; -1 = unknown)
     * @param margin        gap between best and second-best candidate score; large = clear winner
     * @param touchAvg      mean touch-evidence bonus per differing char (>= 0 when no touch data)
     * @param touchDiff     number of characters that were rescored with touch geometry
     * @param isKnownWord   the typed word exists in the dictionary (rare-word rewrite)
     */
    fun score(
        editCost: Float,
        wordLen: Int,
        rank: Int,
        margin: Float,
        touchAvg: Float,
        touchDiff: Int,
        isKnownWord: Boolean
    ): Float {
        if (wordLen < 3) return FLOOR  // the pipeline refuses sub-3-letter words anyway

        var s = 0.55f

        // Frequency prior.
        s += when {
            rank < 0 -> 0.00f
            rank < 100 -> 0.25f
            rank < 1000 -> 0.18f
            rank < 5000 -> 0.10f
            else -> 0.02f
        }

        // Edit cost, normalized per character (a swap in a 12-letter word is cheap).
        val perChar = editCost / wordLen.coerceAtLeast(1)
        s += when {
            perChar <= 0.25f -> 0.15f
            perChar <= 0.40f -> 0.05f
            perChar <= 0.55f -> -0.10f
            else -> -0.30f
        }

        // Ambiguity: a coin flip between two candidates must not autocorrect.
        s += when {
            margin >= 1.0f -> 0.15f
            margin >= 0.5f -> 0.08f
            margin >= 0.35f -> 0.0f
            else -> -0.40f
        }

        // Touch evidence: only meaningful when geometry actually rescored something.
        if (touchDiff > 0) {
            s += when {
                touchAvg >= 3.0f -> 0.15f
                touchAvg >= 1.5f -> 0.08f
                touchAvg >= 0.5f -> 0.0f
                else -> -0.15f
            }
        }

        // Length caution.
        s += when {
            wordLen <= 3 -> -0.20f
            wordLen == 4 -> -0.05f
            wordLen >= 6 -> 0.05f
            else -> 0.0f
        }

        // Correcting a real (rare) dictionary word is the riskiest move.
        if (isKnownWord) s -= 0.45f

        return s.coerceIn(FLOOR, CEIL)
    }

    /** Conservative fallback confidence for non-dictionary correction paths. */
    fun fallback(kind: Kind, wordLen: Int): Float {
        val base = when (kind) {
            Kind.NEURAL -> NEURAL_FALLBACK
            Kind.SYSTEM_SPELL -> SYSTEM_SPELL_FALLBACK
        }
        // A tiny length bonus: longer "typo" words are less likely intentional.
        return (base + min(0.05f, (wordLen - 3) * 0.01f)).coerceIn(FLOOR, CEIL)
    }

    enum class Kind { NEURAL, SYSTEM_SPELL }
}
