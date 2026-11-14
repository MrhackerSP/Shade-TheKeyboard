package com.noircipher.shade.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the standalone autocorrect confidence scorer.
 *
 * The scale contract: classic unambiguous typos must clear the 0.85 gate,
 * ambiguous near-ties and known-word rewrites must sit clearly below it.
 */
class CorrectionConfidenceTest {

    private val GATE = 0.85f

    @Test fun classicFrequentTypoClearsGate() {
        // "teh" -> "the": swap (cost 0.7), rank ~1, uncontested margin.
        val s = CorrectionConfidence.score(
            editCost = 0.7f, wordLen = 3, rank = 1, margin = 10f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        assertTrue("expected >= 0.85, got $s", s >= GATE)
    }

    @Test fun longWordSingleSubClearsGate() {
        // "definately" -> "definitely": one far substitution in a 10-letter word.
        val s = CorrectionConfidence.score(
            editCost = 1.0f, wordLen = 10, rank = 800, margin = 1.2f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        assertTrue("expected >= 0.85, got $s", s >= GATE)
    }

    @Test fun ambiguousMarginSuppressesCorrection() {
        val ambiguous = CorrectionConfidence.score(
            editCost = 0.7f, wordLen = 5, rank = 100, margin = 0.1f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        val clear = CorrectionConfidence.score(
            editCost = 0.7f, wordLen = 5, rank = 100, margin = 1.5f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        assertTrue("ambiguous ($ambiguous) must be below clear ($clear)", ambiguous < clear)
        assertTrue("ambiguous must sit well below the gate", ambiguous < GATE - 0.2f)
    }

    @Test fun knownWordRewriteIsPenalized() {
        val known = CorrectionConfidence.score(
            editCost = 0.6f, wordLen = 5, rank = 100, margin = 1.5f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = true
        )
        val unknown = CorrectionConfidence.score(
            editCost = 0.6f, wordLen = 5, rank = 100, margin = 1.5f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        assertTrue(known < unknown)
        assertTrue("known-word rewrite must not clear the gate on this evidence", known < GATE)
    }

    @Test fun strongTouchEvidenceBoostsConfidence() {
        val weak = CorrectionConfidence.score(
            editCost = 1.0f, wordLen = 6, rank = 2000, margin = 0.6f,
            touchAvg = 0.3f, touchDiff = 2, isKnownWord = false
        )
        val strong = CorrectionConfidence.score(
            editCost = 1.0f, wordLen = 6, rank = 2000, margin = 0.6f,
            touchAvg = 4.0f, touchDiff = 2, isKnownWord = false
        )
        assertTrue(strong > weak)
    }

    @Test fun rareCandidateWithoutEvidenceStaysLow() {
        val s = CorrectionConfidence.score(
            editCost = 2.5f, wordLen = 6, rank = -1, margin = 0.2f,
            touchAvg = 0f, touchDiff = 0, isKnownWord = false
        )
        assertTrue("expected < 0.85, got $s", s < GATE)
    }

    @Test fun shortWordsAreRefused() {
        assertEquals(0f, CorrectionConfidence.score(0.3f, 2, 1, 10f, 0f, 0, false), 0.0001f)
    }

    @Test fun scoreIsClampedToUnitRange() {
        val max = CorrectionConfidence.score(0f, 10, 0, 10f, 5f, 5, false)
        val min = CorrectionConfidence.score(30f, 3, -1, 0f, -5f, 5, true)
        assertTrue(max <= 1f)
        assertTrue(min >= 0f)
    }

    @Test fun fallbacksSitBelowGate() {
        assertTrue(CorrectionConfidence.fallback(CorrectionConfidence.Kind.NEURAL, 8) < GATE)
        assertTrue(CorrectionConfidence.fallback(CorrectionConfidence.Kind.SYSTEM_SPELL, 8) < GATE)
        // But a system-spell candidate on a long word can pair with a speed-
        // adjusted threshold drop (0.5) and pass.
        assertTrue(CorrectionConfidence.fallback(CorrectionConfidence.Kind.SYSTEM_SPELL, 12) >= 0.5f)
    }
}
