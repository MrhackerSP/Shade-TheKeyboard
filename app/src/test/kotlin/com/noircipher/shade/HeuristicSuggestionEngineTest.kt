package com.noircipher.shade

import com.noircipher.shade.engine.HeuristicSuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicSuggestionEngineTest {
    private val timeWords = setOf("morning", "afternoon", "evening", "night")

    @Test
    fun phraseContinuationsWork() {
        assertEquals("are", HeuristicSuggestionEngine.phrase("how", null, 1).firstOrNull())
        assertEquals("you", HeuristicSuggestionEngine.phrase("are", null, 1).firstOrNull())
    }

    @Test
    fun trigramContinuationsWork() {
        assertEquals("doing", HeuristicSuggestionEngine.trigram(listOf("how", "are", "you"), null, 1).firstOrNull())
    }

    @Test
    fun smartStemAndCodeSwitchWork() {
        assertTrue(HeuristicSuggestionEngine.smart(emptyList(), "congratul", 5).contains("congratulations"))
        assertTrue(HeuristicSuggestionEngine.smart(listOf("kya", "कर"), null, 10).isNotEmpty())
    }

    @Test
    fun phraseIgnoresCaseOfPreviousWordAndPrefix() {
        assertEquals(listOf("are"), HeuristicSuggestionEngine.phrase("How", "a", 3))
        // "I" is stored capitalised; typing the lowercase prefix "i" must still find it.
        assertTrue(HeuristicSuggestionEngine.phrase("can", "i", 5).contains("I"))
    }

    @Test
    fun emptyOrInvalidInputGivesNothing() {
        assertTrue(HeuristicSuggestionEngine.phrase(null, null, 5).isEmpty())
        assertTrue(HeuristicSuggestionEngine.phrase("  ", null, 5).isEmpty())
        assertTrue(HeuristicSuggestionEngine.phrase("how", null, 0).isEmpty())
        assertTrue(HeuristicSuggestionEngine.trigram(emptyList(), null, 5).isEmpty())
        assertTrue(HeuristicSuggestionEngine.smart(emptyList(), null, 0).isEmpty())
    }

    @Test
    fun prefixFilterOnlyReturnsMatchingWords() {
        val out = HeuristicSuggestionEngine.phrase("how", "m", 10)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.startsWith("m", ignoreCase = true) })
    }

    @Test
    fun trigramHasNoDuplicates() {
        val out = HeuristicSuggestionEngine.trigram(listOf("i", "will", "be"), null, 10)
        assertEquals(out.distinct(), out)
        assertEquals(1, out.count { it == "there" })
    }

    @Test
    fun timeOfDayWordsOnlyAppearAfterGood() {
        // Regression: "morning"/"evening"/... used to be suggested after any word.
        val afterOther = HeuristicSuggestionEngine.smart(listOf("i", "think"), null, 20)
        assertTrue(afterOther.none { it in timeWords })

        val afterGood = HeuristicSuggestionEngine.smart(listOf("good"), null, 20)
        assertTrue(afterGood.any { it in timeWords })
    }

    @Test
    fun stemCompletionNeverOffersTheWordAsTyped() {
        val out = HeuristicSuggestionEngine.smart(emptyList(), "meet", 10)
        assertFalse(out.any { it.equals("meet", ignoreCase = true) })
        assertTrue(out.contains("meeting"))
    }

    @Test
    fun smartPrefixResultsAllMatchPrefix() {
        val out = HeuristicSuggestionEngine.smart(listOf("thank"), "y", 10)
        assertTrue(out.all { it.startsWith("y", ignoreCase = true) })
        assertTrue(out.contains("you"))
    }
}
