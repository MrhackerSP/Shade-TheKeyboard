package com.noircipher.shade.engine

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the neural spell checker's pure logic against the real bundled
 * `wpm.vocab`, and verifies graceful degradation: on the JVM the TFLite
 * native library is unavailable, so the engine must report unavailable and
 * return no corrections instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralSpellCheckerTest {

    private fun checker(): NeuralSpellChecker =
        NeuralSpellChecker(RuntimeEnvironment.getApplication() as Context)

    @Test
    fun segmentationUsesGreedyLongestMatch() {
        val c = checker()
        assertEquals(listOf("hello"), c.segment("hello"))
        assertEquals(listOf("he", "##lo"), c.segment("helo"))
        assertEquals(listOf("t", "##eh"), c.segment("teh"))
    }

    @Test
    fun segmentationIsCaseInsensitiveAndBounded() {
        val c = checker()
        assertEquals(listOf("hello"), c.segment("HeLLo"))
        assertEquals(NeuralSpellChecker.MAX_PIECES, c.segment("a".repeat(40)).size)
    }

    @Test
    fun matchCaseMirrorsTypedPattern() {
        val c = checker()
        assertEquals("hello", c.matchCase("helo", "Hello"))
        assertEquals("Hello", c.matchCase("Helo", "hello"))
        assertEquals("friend", c.matchCase("FRIEND", "friend"))
        // Mixed-case typed words fall through untouched.
        assertEquals("Friend", c.matchCase("FrIeNd", "Friend"))
    }

    @Test
    fun unavailableEngineReturnsNoCorrectionsWithoutCrashing() {
        val c = checker()
        if (c.isAvailable) {
            // Native TFLite present (e.g. device build): the pipeline itself must work.
            assertTrue(c.correct("helo").isNotEmpty())
        } else {
            // JVM unit-test environment: no native library — degrade to no candidates.
            assertTrue(c.correct("helo").isEmpty())
            assertFalse(c.isAvailable)
        }
    }
}
