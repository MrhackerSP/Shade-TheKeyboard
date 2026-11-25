package com.shade.keyboard

import android.content.Context
import com.shade.keyboard.engine.SuggestionRejectionTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * §3 — locks the rejection tracker's semantics: repeated rejections suppress,
 * single rejections don't, counts decay after the TTL, and the explicit
 * "Remove suggestion" suppress bypasses decay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuggestionRejectionTrackerTest {

    private fun tracker(): SuggestionRejectionTracker =
        SuggestionRejectionTracker(RuntimeEnvironment.getApplication() as Context)

    @Test fun singleRejectionDoesNotSuppress() {
        val t = tracker()
        t.recordRejection("banana")
        assertFalse(t.shouldSuppress("banana"))
    }

    @Test fun repeatedRejectionsSuppress() {
        val t = tracker()
        repeat(3) { t.recordRejection("banana") }
        assertTrue(t.shouldSuppress("banana"))
        assertFalse(t.shouldSuppress("cherry"))
    }

    @Test fun rejectionsDecayAfterTtl() {
        val t = tracker()
        var now = 0L
        t.clock = { now }
        repeat(3) { t.recordRejection("banana") }
        assertTrue(t.shouldSuppress("banana"))
        now = 10L * 60L * 1000L + 1L
        assertFalse(t.shouldSuppress("banana"))
    }

    @Test fun decayedCountMustReaccumulate() {
        val t = tracker()
        var now = 0L
        t.clock = { now }
        repeat(3) { t.recordRejection("banana") }
        now = 10L * 60L * 1000L + 1L
        assertFalse(t.shouldSuppress("banana"))
        t.recordRejection("banana")
        assertFalse(t.shouldSuppress("banana"))
        repeat(2) { t.recordRejection("banana") }
        assertTrue(t.shouldSuppress("banana"))
    }

    @Test fun explicitSuppressBypassesDecay() {
        val t = tracker()
        var now = 0L
        t.clock = { now }
        t.suppress("banana")
        now = 60L * 60L * 1000L
        assertTrue(t.shouldSuppress("banana"))
    }
}
