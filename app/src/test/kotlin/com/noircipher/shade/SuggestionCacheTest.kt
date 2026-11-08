package com.noircipher.shade

import com.noircipher.shade.engine.SuggestionCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuggestionCacheTest {
    private var now = 0L

    @Before fun setUp() {
        now = 0L
        SuggestionCache.clock = { now }
        SuggestionCache.salt = ""
        SuggestionCache.invalidate()
    }

    @After fun tearDown() {
        SuggestionCache.clock = { System.currentTimeMillis() }
        SuggestionCache.salt = ""
        SuggestionCache.invalidate()
    }

    @Test fun storesAndReturnsEntries() {
        SuggestionCache.put("a", "b", "he", listOf("hello", "help"))
        assertEquals(listOf("hello", "help"), SuggestionCache.get("a", "b", "he"))
        assertNull(SuggestionCache.get("a", "b", "hx"))
        assertNull(SuggestionCache.get(null, "b", "he"))
    }

    @Test fun entriesExpireAfterTtl() {
        SuggestionCache.put(null, "a", "x", listOf("xylo"))
        now = 1500
        assertNotNull(SuggestionCache.get(null, "a", "x"))   // still valid at the limit
        now = 1501
        assertNull(SuggestionCache.get(null, "a", "x"))
        assertEquals(0, SuggestionCache.size())              // expired entry removed on read
    }

    @Test fun saltIsolatesEntries() {
        SuggestionCache.salt = "en|false"
        SuggestionCache.put(null, "a", "x", listOf("one"))
        SuggestionCache.salt = "hi|false"
        assertNull(SuggestionCache.get(null, "a", "x"))
        SuggestionCache.salt = "en|false"
        assertEquals(listOf("one"), SuggestionCache.get(null, "a", "x"))
    }

    @Test fun sizeIsBounded() {
        repeat(400) { SuggestionCache.put(null, "p", "k$it", listOf("w")) }
        assertTrue(SuggestionCache.size() <= 256)
    }

    @Test fun invalidatePrefixOnlyRemovesExactPrefix() {
        SuggestionCache.put(null, "a", "he", listOf("1"))
        SuggestionCache.put(null, "a", "she", listOf("2"))
        SuggestionCache.put(null, "a", "hel", listOf("3"))
        SuggestionCache.invalidatePrefix("he")
        assertNull(SuggestionCache.get(null, "a", "he"))
        assertNotNull(SuggestionCache.get(null, "a", "she"))
        assertNotNull(SuggestionCache.get(null, "a", "hel"))
    }

    @Test fun invalidateClearsEverything() {
        SuggestionCache.put(null, "a", "x", listOf("1"))
        SuggestionCache.invalidate()
        assertEquals(0, SuggestionCache.size())
    }
}
