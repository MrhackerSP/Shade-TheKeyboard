package com.noircipher.shade

import com.noircipher.shade.slide.GlideLexicon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideLexiconTest {

    private fun lexOf(vararg words: String) = GlideLexicon.build(words.toList(), FloatArray(words.size) { 0.5f })

    @Test fun keepsOnlyWordsThatCanBeSwiped() {
        val lex = lexOf("hello", "don't", "well-known", "naïve", "a", "aa", "x1", "Hello", "ab")
        assertEquals(listOf("hello", "don't", "well-known", "Hello", "ab"), lex.words.toList())
        assertEquals(5, lex.size)
    }

    @Test fun doubleLettersAreCollapsedAndRemembered() {
        val lex = lexOf("hello")
        assertArrayEquals(byteArrayOf(7, 4, 11, 14), lex.codes[0])
        assertEquals(1 shl 11, lex.doubleMask[0])
    }

    @Test fun apostropheAndHyphenAreNotPartOfThePath() {
        val lex = lexOf("don't", "e-mail")
        assertArrayEquals(byteArrayOf(3, 14, 13, 19), lex.codes[0])
        assertArrayEquals(byteArrayOf(4, 12, 0, 8, 11), lex.codes[1])
    }

    @Test fun wordsMadeOfOneRepeatedLetterAreSkipped() {
        assertEquals(0, lexOf("aa", "mmm").size)
    }

    @Test fun veryLongWordsAreSkipped() {
        assertEquals(0, lexOf("abcdefghijklmnopqrstuvwxy").size) // 25 distinct letters
        assertEquals(1, lexOf("abcdefghijklmnopqrstuvwx").size)  // 24 is the limit
    }

    @Test fun priorsAreClampedToUnitRange() {
        val lex = GlideLexicon.build(listOf("up", "in"), floatArrayOf(1.7f, -0.3f))
        assertEquals(1f, lex.prior[0], 0f)
        assertEquals(0f, lex.prior[1], 0f)
    }

    @Test fun priorsStayAlignedWithTheirWordsWhenSomeAreDropped() {
        val lex = GlideLexicon.build(listOf("up", "x1", "in"), floatArrayOf(0.9f, 0.5f, 0.1f))
        assertEquals(listOf("up", "in"), lex.words.toList())
        assertEquals(0.9f, lex.prior[0], 0f)
        assertEquals(0.1f, lex.prior[1], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPriorCountIsRejected() {
        GlideLexicon.build(listOf("up", "in"), floatArrayOf(0.5f))
    }

    @Test fun priorsFromCountsSpanZeroToOneOnALogScale() {
        val p = GlideLexicon.priorsFromCounts(longArrayOf(10, 1_000, 100_000))
        assertEquals(0f, p[0], 1e-6f)
        assertEquals(0.5f, p[1], 1e-6f)
        assertEquals(1f, p[2], 1e-6f)
    }

    @Test fun commonWordsGetHigherPriors() {
        val p = GlideLexicon.priorsFromCounts(longArrayOf(23_968_348_874L, 6_582_992L, 5L))
        assertTrue(p[0] > p[1] && p[1] > p[2])
    }

    @Test fun equalOrEmptyCountsAreHandled() {
        assertEquals(0.5f, GlideLexicon.priorsFromCounts(longArrayOf(7, 7))[0], 0f)
        assertEquals(0, GlideLexicon.priorsFromCounts(longArrayOf()).size)
        assertEquals(0, GlideLexicon.EMPTY.size)
    }

    @Test fun codeOfIsCaseInsensitiveAndRejectsNonLetters() {
        assertEquals(0, GlideLexicon.codeOf('a'))
        assertEquals(25, GlideLexicon.codeOf('Z'))
        assertEquals(-1, GlideLexicon.codeOf('1'))
        assertEquals(-1, GlideLexicon.codeOf('é'))
    }
}
