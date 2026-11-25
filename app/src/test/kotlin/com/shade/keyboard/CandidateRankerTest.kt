package com.shade.keyboard

import com.shade.keyboard.engine.CandidateRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRankerTest {
    @Test
    fun mergeNextWordDeduplicatesAndLimits() {
        assertEquals(
            listOf("hello", "hi"),
            CandidateRanker.mergeNextWord(
                neural = listOf("hello", "hi", "hey"),
                dictionary = listOf("hello", "hola"),
                limit = 2
            )
        )
    }

    @Test
    fun mergeNextWordDeduplicatesIgnoringCaseAndKeepsFirstSpelling() {
        assertEquals(
            listOf("The", "a"),
            CandidateRanker.mergeNextWord(listOf("The"), listOf("the", "a"), 3)
        )
    }

    @Test
    fun mergeNextWordHandlesEmptyBlankAndZeroLimit() {
        assertTrue(CandidateRanker.mergeNextWord(emptyList(), emptyList(), 3).isEmpty())
        assertTrue(CandidateRanker.mergeNextWord(listOf("a"), emptyList(), 0).isEmpty())
        assertEquals(listOf("hi"), CandidateRanker.mergeNextWord(listOf(" ", "hi"), emptyList(), 3))
    }

    @Test
    fun neuralConfidenceOutranksDictionary() {
        val out = CandidateRanker.mergeNextWordScored(
            neural = listOf("x" to 1f),
            dictionary = listOf("y" to 1f),
            limit = 2
        )
        assertEquals(listOf("x", "y"), out)
    }

    @Test
    fun nonFiniteScoresDoNotBreakRanking() {
        val out = CandidateRanker.mergeNextWordScored(
            neural = listOf("x" to Float.NaN, "y" to Float.POSITIVE_INFINITY),
            limit = 3
        )
        assertEquals(setOf("x", "y"), out.toSet())
    }

    @Test
    fun emojiFallbackFillsOnlyFreeSlotsWithoutDuplicates() {
        assertEquals(
            listOf("a", "😀", "😂"),
            CandidateRanker.withEmojiFallback(listOf("a"), listOf("😀", "😂", "a"), 3)
        )
        assertEquals(
            listOf("a", "b", "c"),
            CandidateRanker.withEmojiFallback(listOf("a", "b", "c"), listOf("😀"), 3)
        )
    }

    @Test
    fun rankContextualPrefersHigherNeuralScoreAndKeepsCase() {
        val out = CandidateRanker.rankContextual(
            candidates = listOf("Alpha", "Beta"),
            neuralScores = mapOf("beta" to 1f, "alpha" to 0.1f)
        )
        assertEquals(listOf("Beta", "Alpha"), out)
    }

    @Test
    fun rankContextualEmptyInputGivesEmpty() {
        assertTrue(CandidateRanker.rankContextual(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun scoredByPositionGivesMonotonicPositionalPrior() {
        assertEquals(
            listOf("a" to 1f, "b" to 0.5f, "c" to 0.25f),
            CandidateRanker.scoredByPosition(listOf("a", "b", "c"))
        )
    }

    @Test
    fun scoredSourcesTieOnTheirOwnLeadersNotConcatenationOrder() {
        // Each source's leader carries the same normalized confidence; the
        // merge breaks the tie by first-seen order. Under the old positional
        // concatenation the second source's leader could never catch up.
        val merged = CandidateRanker.mergeNextWordScored(
            neural = CandidateRanker.scoredByPosition(listOf("alpha")) +
                CandidateRanker.scoredByPosition(listOf("beta")),
            limit = 3
        )
        assertEquals(listOf("alpha", "beta"), merged)
    }
}
