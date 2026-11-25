package com.shade.keyboard

import com.shade.keyboard.engine.SuggestionFinalizer
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

    // ------------------------------------------------------------- mergeScored (§2)

    @Test fun mergeScoredKeepsMlOrderWithoutInjections() {
        assertEquals(
            listOf("a", "b", "c"),
            SuggestionFinalizer.mergeScored(listOf("a", "b", "c"), emptyList())
        )
    }

    @Test fun mergeScoredWeightedSourceDisplacesOnlyWeakMlSlots() {
        // ML priors: 1.0, 0.5, 0.33, 0.25. A 0.60-weight source: entry 0 → 0.60,
        // entry 1 → 0.30. Sorted: ml0(1.0), time0(0.60), ml1(0.5), ml2(0.33),
        // time1(0.30), ml3(0.25).
        val merged = SuggestionFinalizer.mergeScored(
            ranked = listOf("ml0", "ml1", "ml2", "ml3"),
            injections = listOf(0.60f to listOf("time0", "time1"))
        )
        assertEquals(listOf("ml0", "time0", "ml1", "ml2", "time1", "ml3"), merged)
    }

    @Test fun mergeScoredMlLeaderAlwaysSurvives() {
        val merged = SuggestionFinalizer.mergeScored(
            ranked = listOf("ml0", "ml1"),
            injections = listOf(0.95f to listOf("inj0"))
        )
        assertEquals("ml0", merged.first())
        assertEquals("inj0", merged[1])
    }

    @Test fun mergeScoredDeduplicatesKeepingBestScoreAndFirstCasing() {
        val merged = SuggestionFinalizer.mergeScored(
            ranked = listOf("good"),
            injections = listOf(0.9f to listOf("good", "morning"))
        )
        assertEquals(listOf("good", "morning"), merged)
    }

    @Test fun mergeScoredEmptyInjectionListsReturnRankedUnchanged() {
        val ranked = listOf("a", "b")
        assertEquals(ranked, SuggestionFinalizer.mergeScored(ranked, listOf(0.9f to emptyList())))
    }
}
