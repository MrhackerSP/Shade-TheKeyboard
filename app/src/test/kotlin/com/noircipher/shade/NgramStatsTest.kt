package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgramStatsTest {
    @Test fun emptyWordIsIgnored() {
        val s = NgramStats()
        assertFalse(s.observe(null, "how", ""))
        assertTrue(s.predict(null, "how", 3).isEmpty())
    }

    @Test fun mostFrequentNextWordWins() {
        val s = NgramStats()
        repeat(3) { s.observe(null, "how", "are") }
        s.observe(null, "how", "do")
        val out = s.predict(null, "how", 2)
        assertEquals("are", out[0].first)
        assertEquals("do", out[1].first)
        assertTrue(out[0].second > out[1].second)
    }

    @Test fun lookupIsCaseInsensitive() {
        val s = NgramStats()
        s.observe(null, "How", "ARE")
        assertEquals("are", s.predict(null, "hOW", 1).single().first)
    }

    @Test fun trigramContextBeatsBigramContext() {
        val s = NgramStats()
        repeat(5) { s.observe(null, "are", "they") }
        repeat(2) { s.observe("how", "are", "you") }
        assertEquals("you", s.predict("how", "are", 1).single().first)
    }

    @Test fun noPrevWordOrZeroMaxGivesNothing() {
        val s = NgramStats()
        s.observe(null, "a", "b")
        assertTrue(s.predict(null, null, 3).isEmpty())
        assertTrue(s.predict(null, "a", 0).isEmpty())
    }

    @Test fun countsStayConsistentWithTotalsAfterManyObservations() {
        // Regression: only the observed word used to decay, so count/total could exceed 1.
        val s = NgramStats()
        repeat(5000) { i ->
            s.observe("x", "a", if (i % 7 == 0) "rare" else "common")
            s.observe(null, "a", if (i % 3 == 0) "other" else "common")
        }
        for ((ctx, m) in s.bigrams) {
            val sum = m.values.sum()
            assertEquals(ctx, s.bigramTotals[ctx]!!.toDouble(), sum.toDouble(), sum * 1e-3 + 1e-3)
        }
        for ((ctx, m) in s.trigrams) {
            val sum = m.values.sum()
            assertEquals(ctx, s.trigramTotals[ctx]!!.toDouble(), sum.toDouble(), sum * 1e-3 + 1e-3)
        }
        assertTrue(s.predict("x", "a", 5).all { it.second <= 1.0001f })
    }

    @Test fun oldHabitsFadeInFavourOfRecentOnes() {
        val s = NgramStats()
        repeat(20) { s.observe(null, "a", "old") }
        repeat(2000) { s.observe(null, "a", "new") }
        assertEquals("new", s.predict(null, "a", 1).single().first)
    }

    @Test fun staticTableScoresNeverGoNegativeAndKeepOrder() {
        val s = NgramStats()
        val table = mapOf("a" to arrayOf("w0", "w1", "w2", "w3", "w4", "w5"))
        val out = s.predict(null, "a", 10, table)
        assertEquals(6, out.size)
        assertTrue(out.all { it.second > 0f })
        assertEquals("w0", out.first().first)
    }

    @Test fun rerankOrdersByPredictionAndLeavesUnknownsLast() {
        val s = NgramStats()
        repeat(2) { s.observe(null, "a", "c") }
        assertEquals(listOf("c", "b"), s.rerank(null, "a", listOf("b", "c")))
        assertEquals(listOf("b", "c"), s.rerank(null, null, listOf("b", "c")))
        assertTrue(s.rerank(null, "a", emptyList()).isEmpty())
    }

    @Test fun clearForgetsEverything() {
        val s = NgramStats()
        s.observe("x", "a", "b")
        s.clear()
        assertTrue(s.predict("x", "a", 3).isEmpty())
        assertTrue(s.bigrams.isEmpty() && s.trigrams.isEmpty())
    }
}
