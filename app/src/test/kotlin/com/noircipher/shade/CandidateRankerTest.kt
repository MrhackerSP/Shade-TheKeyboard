package com.noircipher.shade

import com.noircipher.shade.engine.CandidateRanker
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
}
