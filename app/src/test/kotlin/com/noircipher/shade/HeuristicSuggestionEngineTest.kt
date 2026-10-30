package com.noircipher.shade

import com.noircipher.shade.engine.HeuristicSuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicSuggestionEngineTest {
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
}
