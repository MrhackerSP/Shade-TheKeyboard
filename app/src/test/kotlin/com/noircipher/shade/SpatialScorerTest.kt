package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialScorerTest {

    /** QWERTY with 100x100 keys; row 2 offset by half a key, row 3 by one and a half. */
    private val layout: Map<Char, Key> = buildMap {
        val rows = listOf(Triple("qwertyuiop", 0f, 0f), Triple("asdfghjkl", 50f, 100f), Triple("zxcvbnm", 150f, 200f))
        for ((letters, offset, rowTop) in rows) {
            letters.forEachIndexed { i, c ->
                put(c, Key(c.toString(), c.code).apply {
                    left = offset + i * 100f; right = left + 100f
                    top = rowTop; bottom = rowTop + 100f
                })
            }
        }
    }

    private fun center(c: Char) = floatArrayOf(layout.getValue(c).centerX, layout.getValue(c).centerY)
    private fun touchesFor(word: String) = word.map { center(it) }
    private fun offsetFrom(c: Char, dx: Float, dy: Float) = listOf(center(c).let { floatArrayOf(it[0] + dx, it[1] + dy) })

    // ---- fit (higher = better) ----

    @Test fun fitIsOneOnCentreAndFallsWithDistance() {
        assertEquals(1f, SpatialScorer.fit("a", touchesFor("a"), layout), 1e-4f)
        val near = SpatialScorer.fit("a", offsetFrom('a', 20f, 0f), layout)
        val far = SpatialScorer.fit("a", offsetFrom('a', 80f, 0f), layout)
        assertTrue(near in 0f..1f && far in 0f..1f)
        assertTrue("$near > $far", near > far)
        assertTrue(SpatialScorer.fit("a", offsetFrom('a', 500f, 500f), layout) < 0.001f)
    }

    @Test fun verticalErrorCostsMoreThanHorizontalError() {
        val horizontal = SpatialScorer.fit("a", offsetFrom('a', 30f, 0f), layout)
        val vertical = SpatialScorer.fit("a", offsetFrom('a', 0f, 30f), layout)
        assertEquals(0.80f, horizontal, 0.01f)
        assertEquals(0.69f, vertical, 0.01f)
        assertTrue(horizontal > vertical)
    }

    @Test fun fitIgnoresLettersWithoutAKeyAndAveragesTheRest() {
        // "1" has no key: it is skipped, not counted as a miss.
        assertEquals(1f, SpatialScorer.fit("a1", listOf(center('a'), floatArrayOf(0f, 0f)), layout), 1e-4f)
        assertEquals(0f, SpatialScorer.fit("123", touchesFor("abc"), layout), 0f)
    }

    @Test fun fitHandlesEmptyInputsAndLengthMismatch() {
        assertEquals(0f, SpatialScorer.fit("", touchesFor("a"), layout), 0f)
        assertEquals(0f, SpatialScorer.fit("abc", emptyList(), layout), 0f)
        // Only the letters that have a touch are compared.
        assertEquals(1f, SpatialScorer.fit("was", touchesFor("wa"), layout), 1e-4f)
        assertEquals(1f, SpatialScorer.fit("wa", touchesFor("was"), layout), 1e-4f)
    }

    @Test fun fitIsCaseInsensitive() {
        assertEquals(1f, SpatialScorer.fit("WAS", touchesFor("was"), layout), 1e-4f)
    }

    // ---- tiebreak ----

    @Test fun tiebreakPutsTheClosestMatchFirst() {
        val touches = touchesFor("was")
        assertEquals(listOf("was", "wad", "wax"), SpatialScorer.tiebreak(listOf("wax", "was", "wad"), touches, layout))
        assertEquals(listOf("was", "wad", "wax"), SpatialScorer.tiebreak(listOf("wad", "wax", "was"), touches, layout))
    }

    @Test fun tiebreakLeavesTrivialInputsAlone() {
        val touches = touchesFor("was")
        assertEquals(listOf("only"), SpatialScorer.tiebreak(listOf("only"), touches, layout))
        assertEquals(listOf("b", "a"), SpatialScorer.tiebreak(listOf("b", "a"), emptyList(), layout))
        assertEquals(listOf("b", "a"), SpatialScorer.tiebreak(listOf("b", "a"), touches, emptyMap()))
    }

    @Test fun tiebreakKeepsOriginalOrderForEqualFits() {
        val touches = touchesFor("a")
        assertEquals(listOf("a", "a"), SpatialScorer.tiebreak(listOf("a", "a"), touches, layout))
        assertEquals(listOf("1", "2"), SpatialScorer.tiebreak(listOf("1", "2"), touches, layout))
    }

    // ---- score (legacy feature for the ranking model) ----

    @Test fun legacyScoreKeepsItsSignBecauseTheRankingModelUsesIt() {
        // score() is negative and *lowest* for a perfect touch. It feeds a trained model
        // (RankingFeatureExtractor), so changing it needs retraining; use fit()/tiebreak() to rank.
        assertEquals(-1f, SpatialScorer.score("a", touchesFor("a"), layout), 1e-4f)
        assertTrue(SpatialScorer.score("a", offsetFrom('a', 500f, 500f), layout) > -0.001f)
        val touches = touchesFor("was")
        assertTrue(SpatialScorer.score("was", touches, layout) < SpatialScorer.score("wax", touches, layout))
        assertTrue(SpatialScorer.fit("was", touches, layout) > SpatialScorer.fit("wax", touches, layout))
    }

    @Test fun legacyScoreHandlesEmptyInputs() {
        assertEquals(0f, SpatialScorer.score("", touchesFor("a"), layout), 0f)
        assertEquals(0f, SpatialScorer.score("a", emptyList(), layout), 0f)
    }
}
