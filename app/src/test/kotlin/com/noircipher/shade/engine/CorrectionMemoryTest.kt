package com.noircipher.shade.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the personal correction memory (SharedPreferences
 * store). Covers the accept -> pre-approve and revert -> unlearn cycles that
 * the autocorrect precision pipeline depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorrectionMemoryTest {

    private fun memory() = CorrectionMemory(RuntimeEnvironment.getApplication())

    @Test fun belowThresholdIsNotApproved() {
        val m = memory()
        m.record("teh", "the")
        assertFalse("one accept must not pre-approve", m.approves("teh", "the"))
        assertNull(m.recall("teh"))
    }

    @Test fun repeatedAcceptPreapproves() {
        val m = memory()
        m.record("teh", "the")
        m.record("teh", "the")
        assertTrue(m.approves("teh", "the"))
        assertEquals("the", m.recall("teh")!!.corrected)
        assertEquals(2, m.recall("teh")!!.count)
    }

    @Test fun conflictingFixRestartsCount() {
        val m = memory()
        m.record("thier", "their")
        m.record("thier", "their")
        assertTrue(m.approves("thier", "their"))
        // User changed their mind: a different fix restarts the count at 1.
        m.record("thier", "theier")
        val entry = m.recall("thier")
        assertEquals("theier", entry!!.corrected)
        assertEquals(1, entry.count)
        assertFalse(m.approves("thier", "theier"))
        assertFalse(m.approves("thier", "their"))
    }

    @Test fun revertUnlearns() {
        val m = memory()
        m.record("teh", "the")
        m.record("teh", "the")
        assertTrue(m.approves("teh", "the"))
        // One revert penalizes by 2 -> count 0 -> entry gone.
        m.unlearn("teh", "the")
        assertFalse(m.approves("teh", "the"))
        assertNull(m.recall("teh"))
    }

    @Test fun revertOnlyAffectsTheSamePair() {
        val m = memory()
        m.record("teh", "the")
        m.record("teh", "the")
        m.unlearn("teh", "tech")  // different fix: no-op
        assertTrue(m.approves("teh", "the"))
    }

    @Test fun invalidPairsAreIgnored() {
        val m = memory()
        m.record("teh", "teh")          // identical: no-op
        m.record("a", "the")            // too short
        m.record("user@example.com", "user")  // not word chars
        assertEquals(0, m.size())
    }

    @Test fun storeIsCaseInsensitive() {
        val m = memory()
        m.record("Teh", "The")
        m.record("TEH", "the")
        assertTrue(m.approves("teh", "the"))
    }

    @Test fun survivesReload() {
        val ctx = RuntimeEnvironment.getApplication()
        val m1 = CorrectionMemory(ctx)
        m1.record("definately", "definitely")
        m1.record("definately", "definitely")
        val m2 = CorrectionMemory(ctx)
        assertTrue(m2.approves("definately", "definitely"))
    }

    @Test fun clearWipesEverything() {
        val m = memory()
        m.record("teh", "the")
        m.record("teh", "the")
        m.clear()
        assertEquals(0, m.size())
        assertFalse(m.approves("teh", "the"))
    }

    @Test fun boundedGrowth() {
        val m = memory()
        for (i in 0 until 400) {
            m.record("word$i", "fixed$i")
            m.record("word$i", "fixed$i")  // counts don't matter for eviction
        }
        assertTrue("store must stay bounded (300 + slack), got ${m.size()}", m.size() <= 310)
    }

    @Test fun multiWordFixesAreStored() {
        val m = memory()
        m.record("alot", "a lot")
        m.record("alot", "a lot")
        assertTrue(m.approves("alot", "a lot"))
    }
}
