package com.noircipher.shade

import android.content.Context
import com.noircipher.shade.engine.ProfanityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Block offensive words" — the suggestion-level profanity gate.
 *
 * The pins below use hex literals computed independently by
 * scripts/gen_offensive_hashes.py (Python hashlib), so the Kotlin
 * MessageDigest path is cross-checked against a second implementation.
 * Words appear here only as test vectors for those hashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfanityFilterTest {

    // Hashes for: fuck, shitting, nigger — produced by the Python generator.
    private val pinned = listOf(
        "6ac3c336e4094835",
        "b2a7d488cf78ce74",
        "120f6e5b4ea32f65"
    )

    private fun loadFixture(vararg extra: String) {
        ProfanityFilter.load((pinned + extra).asSequence())
    }

    @Test
    fun sha16MatchesThePythonGenerator() {
        assertEquals("6ac3c336e4094835", ProfanityFilter.sha16("fuck"))
        assertEquals("b2a7d488cf78ce74", ProfanityFilter.sha16("shitting"))
        assertEquals("120f6e5b4ea32f65", ProfanityFilter.sha16("nigger"))
    }

    @Test
    fun exactBlockedFormsAreFlaggedCaseInsensitively() {
        loadFixture()
        assertTrue(ProfanityFilter.isOffensive("fuck"))
        assertTrue(ProfanityFilter.isOffensive("FUCK"))
        assertTrue(ProfanityFilter.isOffensive("  Shitting  "))
        assertTrue(ProfanityFilter.isOffensive("NIGGER"))
    }

    @Test
    fun otherInflectionsStayBlockedOnlyIfTheirOwnHashShips() {
        loadFixture()
        // Exact-form matching: "fucking" is NOT in the fixture, so it passes —
        // the shipped asset carries every real inflection's own hash instead
        // of using dangerous prefix rules.
        assertFalse(ProfanityFilter.isOffensive("fucking"))
    }

    @Test
    fun legitimateWordsAreNeverSuppressed() {
        loadFixture()
        // The classic false-positive traps of prefix matching — exact-form
        // matching makes all of these structurally impossible.
        for (safe in listOf("asset", "assess", "assist", "class", "classify",
                            "shift", "shirt", "buttress", "button", "passion",
                            "document", "shitake", "shittimwood")) {
            assertFalse("must not flag $safe", ProfanityFilter.isOffensive(safe))
        }
    }

    @Test
    fun punctuationNumbersAndShortTokensPassThrough() {
        loadFixture()
        assertFalse(ProfanityFilter.isOffensive("fuck!"))
        assertFalse(ProfanityFilter.isOffensive("f**k"))
        assertFalse(ProfanityFilter.isOffensive("123456"))
        assertFalse(ProfanityFilter.isOffensive("fu"))
        assertFalse(ProfanityFilter.isOffensive(""))
        assertFalse(ProfanityFilter.isOffensive("   "))
    }

    @Test
    fun disabledFilterOrMissingListIsInert() {
        loadFixture()
        try {
            ProfanityFilter.enabled = false
            assertFalse(ProfanityFilter.isOffensive("fuck"))
        } finally {
            ProfanityFilter.enabled = true
        }
        // Fresh object state with no hashes loaded.
        ProfanityFilter.load(emptySequence())
        assertEquals(0, ProfanityFilter.loadedCount)
        assertFalse(ProfanityFilter.isOffensive("fuck"))
    }

    @Test
    fun malformedLinesAreIgnoredNotFatal() {
        ProfanityFilter.load(sequenceOf(
            "# comment", "", "   ", "zzz", "ABCDEFGH01234567", "123",
            "6ac3c336e4094835!", "b2a7d488cf78ce74"
        ))
        // Only the one well-formed lowercase hex line survives; the uppercase
        // form, the punctuated one and every short/junk line are dropped.
        assertEquals(1, ProfanityFilter.loadedCount)
        assertFalse(ProfanityFilter.isOffensive("fuck"))
        assertTrue(ProfanityFilter.isOffensive("shitting"))
    }

    /** The shipped asset: size, format, ordering, and the pinned hashes. */
    @Test
    fun shippedOffensiveAssetIsWellFormed() {
        val context: Context = RuntimeEnvironment.getApplication()
        val raw = context.assets.open("dicts/en_offensive.txt").bufferedReader().readLines()
        val data = raw.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("offensive list too small: ${data.size} hashes", data.size >= 500)
        assertTrue(data.all { it.length == 16 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
        assertEquals("generator emits a sorted, deduplicated list", data, data.sorted())
        assertEquals("no duplicate hashes in the shipped asset", data.size, data.toSet().size)
        for (hash in pinned) assertTrue("pinned hash $hash missing", hash in data)

        // End-to-end through the loader.
        ProfanityFilter.load(data.asSequence())
        assertTrue(ProfanityFilter.loadedCount >= 500)
        assertTrue(ProfanityFilter.isOffensive("fuck"))
        assertTrue(ProfanityFilter.isOffensive("shitting"))
    }
}
