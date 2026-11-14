package com.noircipher.shade.engine

import android.util.Log
import com.noircipher.shade.SystemSpell

/**
 * Merges Shade's own autocorrection with the system spell checker's results.
 *
 * The system spell checker (SystemSpell) runs asynchronously. When its result
 * arrives, this class merges it with the keyboard's own correction:
 *
 * - If both agree on the same correction → use it (high confidence).
 * - If the system says "typo" and has suggestions → merge with our candidates.
 * - If the system says "in dictionary, not typo" → respect that (don't correct).
 * - If the system hasn't responded yet → use our own correction.
 *
 * The merge is weighted: our correction gets a context bonus (we know the
 * previous word), the system's gets a reliability bonus (it has a full
 * language model).
 */
class SpellMerge {

    data class MergedResult(
        val correction: String?,
        val confidence: Float,  // 0..1
        val source: Source
    )

    enum class Source { OWN, SYSTEM, MERGED, NONE }

    /**
     * Merge our correction with the system spell checker result.
     *
     * @param ownCorrection Our autocorrection (from SpatialCorrector), or null.
     * @param ownScore Our correction's score (lower = better), or Float.MAX_VALUE.
     * @param systemResult The system spell checker's result, or null (not yet available).
     * @param typed The word as typed.
     * @return The merged result.
     */
    fun merge(
        ownCorrection: String?,
        ownScore: Float,
        systemResult: SystemSpell.Result?,
        typed: String
    ): MergedResult {
        val lower = typed.lowercase()

        // Case 1: System says the word is fine (in dictionary, not a typo)
        if (systemResult != null && systemResult.inDictionary && !systemResult.typo) {
            // System is authoritative: don't correct
            if (ownCorrection != null && ownCorrection.lowercase() != lower) {
                // Our correction disagrees — defer to system but note the conflict
                Log.d("SpellMerge", "System says '$lower' is valid; suppressing our '$ownCorrection'")
            }
            return MergedResult(null, 1f, Source.SYSTEM)
        }

        // Case 2: System says it's a typo with suggestions
        if (systemResult != null && systemResult.typo) {
            val sysSuggestions = systemResult.suggestions

            if (ownCorrection != null) {
                // Both have opinions: merge
                val ownInSystem = ownCorrection.lowercase() in sysSuggestions.map { it.lowercase() }

                if (ownInSystem) {
                    // Both agree → high confidence
                    return MergedResult(ownCorrection, 0.9f, Source.MERGED)
                }

                // Disagree: prefer our correction (we have context + touch data)
                // but lower the confidence
                return MergedResult(ownCorrection, 0.5f, Source.OWN)
            }

            // We have no correction but system does
            if (sysSuggestions.isNotEmpty()) {
                // Use the system's top suggestion
                val best = sysSuggestions.first()
                if (best.lowercase() != lower) {
                    return MergedResult(best, 0.6f, Source.SYSTEM)
                }
            }
        }

        // Case 3: No system result yet (or system has no opinion)
        if (ownCorrection != null) {
            return MergedResult(ownCorrection, 0.7f, Source.OWN)
        }

        // Case 4: Neither has a correction
        return MergedResult(null, 0f, Source.NONE)
    }
}
