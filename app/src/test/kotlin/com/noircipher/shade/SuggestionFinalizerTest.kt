package com.noircipher.shade

import com.noircipher.shade.engine.SuggestionFinalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionFinalizerTest {
    private fun fin(
        prefix: String,
        known: Boolean,
        ranked: List<String>,
        grammarTop: String? = null,
        phrases: Boolean = true
    ) = SuggestionFinalizer.finalize(prefix, known, grammarTop, ranked, phrases)

    @Test fun centerBestPutsBestInMiddle() {
        assertEquals(emptyList<String>(), SuggestionFinalizer.centerBest(emptyList()))
        assertEquals(listOf("a"), SuggestionFinalizer.centerBest(listOf("a")))
        assertEquals(listOf("b", "a"), SuggestionFinalizer.centerBest(listOf("a", "b")))
        assertEquals(listOf("b", "a", "c"), SuggestionFinalizer.centerBest(listOf("a", "b", "c")))
    }

    @Test fun knownWordShowsCenteredTopThree() {
        assertEquals(listOf("hello", "help", "held"), fin("hel", true, listOf("help", "hello", "held", "helm")))
    }

    @Test fun unknownWordKeepsTypedWordFirst() {
        assertEquals(listOf("teh", "the", "ten"), fin("teh", false, listOf("the", "ten", "tea", "tee")))
    }

    @Test fun unknownWordIsNotRepeatedIfAlsoRanked() {
        assertEquals(listOf("xyz", "xylo"), fin("xyz", false, listOf("xyz", "xylo")))
        assertEquals(listOf("Xyz", "xylo"), fin("Xyz", false, listOf("xyz", "xylo")))
    }

    @Test fun grammarCorrectionLeadsWhenWordIsUnknown() {
        assertEquals(listOf("there", "them", "their"), fin("their", false, listOf("them"), grammarTop = "there"))
    }

    @Test fun grammarCorrectionIgnoredWhenWordIsKnown() {
        assertEquals(listOf("their", "them"), fin("their", true, listOf("them", "their"), grammarTop = "there"))
    }

    @Test fun grammarCorrectionEqualToTypedWordIsIgnored() {
        assertEquals(listOf("The", "thee"), fin("The", false, listOf("thee"), grammarTop = "the"))
    }

    @Test fun emptyRankedFallsBackToTypedWord() {
        assertEquals(listOf("abc"), fin("abc", true, emptyList()))
        assertEquals(listOf("abc"), fin("abc", false, emptyList()))
    }

    @Test fun wordByWordModeDropsMultiWordCandidates() {
        val ranked = listOf("how are", "hot", "hop")
        assertEquals(listOf("hot", "hop"), fin("ho", true, ranked, phrases = false))
        assertEquals(listOf("hot", "how are", "hop"), fin("ho", true, ranked, phrases = true))
    }

    @Test fun wordByWordModeNeverLeavesTheStripEmpty() {
        assertEquals(listOf("how are"), fin("ho", true, listOf("how are"), phrases = false))
    }
}
