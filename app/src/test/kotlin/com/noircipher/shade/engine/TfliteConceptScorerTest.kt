package com.noircipher.shade.engine

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the concept-triggered emoji scorer (#26) against the real bundled
 * rule tables. On the JVM the TFLite native library is unavailable, so the
 * model layer must disable itself while the curated rules keep working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteConceptScorerTest {

    private fun scorer(): TfliteConceptScorer =
        TfliteConceptScorer(RuntimeEnvironment.getApplication() as Context)

    @Test
    fun rulesKeepScorerAvailableWithoutNativeTflite() {
        val s = scorer()
        // The model path is off on the JVM, but the curated rules must be live.
        assertTrue(s.isAvailable)
    }

    @Test
    fun directEmojiRuleFires() {
        val s = scorer()
        val out = s.suggestEmojis("good bye", 5)
        assertTrue(out.contains("👋"))
    }

    @Test
    fun conceptRuleMapsToEmojis() {
        val s = scorer()
        val out = s.suggestEmojis("yaaaaay", 5)
        // 'yaaaaay' -> high_five/awesome/celebration; celebration's top emoji is 🎉.
        assertTrue(out.contains("🎉"))
    }

    @Test
    fun phraseTriggerMatchesAtQueryTail() {
        val s = scorer()
        val out = s.suggestEmojis("oh good bye", 5)
        assertTrue(out.contains("👋"))
    }

    @Test
    fun blankQueryReturnsEmpty() {
        val s = scorer()
        assertTrue(s.suggestEmojis("", 5).isEmpty())
        assertTrue(s.suggestEmojis("   ", 5).isEmpty())
    }

    @Test
    fun closeWithoutModelDoesNotThrow() {
        // The model path is null on the JVM; close must be a no-op, not a crash.
        scorer().close()
    }
}
