package com.noircipher.shade.engine

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the neural next-word scorer (#25) against the real bundled vocab.
 * On the JVM the TFLite native library is unavailable, so the engine must
 * report unavailable and return no suggestions instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteNextWordScorerTest {

    private fun scorer(): TfliteNextWordScorer =
        TfliteNextWordScorer(RuntimeEnvironment.getApplication() as Context)

    @Test
    fun jvmWithoutNativeTfliteReportsUnavailable() {
        val s = scorer()
        assertFalse(s.isAvailable)
    }

    @Test
    fun unavailableEngineReturnsNoSuggestions() {
        val s = scorer()
        assertTrue(s.suggest(listOf("good"), 2).isEmpty())
        assertTrue(s.suggest(emptyList(), 2).isEmpty())
    }

    @Test
    fun closeWithoutModelDoesNotThrow() {
        // The interpreter is null on the JVM; close must be a no-op, not a crash.
        scorer().close()
    }
}
