package com.noircipher.shade

import com.noircipher.shade.engine.GrammarEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class GrammarEngineTest {
    @Test
    fun dontBecomesDonT() {
        assertEquals("don't", GrammarEngine.suggest(emptyList(), "dont", 1).firstOrNull())
    }

    @Test
    fun heDontBecomesHeDoesnT() {
        assertEquals("doesn't", GrammarEngine.suggest(listOf("he"), "dont", 1).firstOrNull())
    }

    @Test
    fun iIsBecomesIAm() {
        assertEquals("am", GrammarEngine.suggest(listOf("i"), "is", 1).firstOrNull())
    }

    @Test
    fun theyHasBecomesTheyHave() {
        assertEquals("have", GrammarEngine.suggest(listOf("they"), "has", 1).firstOrNull())
    }

    @Test
    fun subjectNextPrediction() {
        assertEquals("am", GrammarEngine.predictNext(listOf("i"), 1).firstOrNull())
        assertEquals("is", GrammarEngine.predictNext(listOf("she"), 1).firstOrNull())
    }
}
