package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure heuristics extracted from ShadeService. */
class SuggestionHeuristicsTest {

    @Test
    fun sensitiveKeysAreDetected() {
        assertTrue(SuggestionHeuristics.isLikelySensitiveText("password: hunter2"))
        assertTrue(SuggestionHeuristics.isLikelySensitiveText("api_key=abc123def456"))
        assertTrue(SuggestionHeuristics.isLikelySensitiveText("my OTP  :  993341"))
    }

    @Test
    fun passwordLikeTokensAreDetected() {
        assertTrue(SuggestionHeuristics.isLikelySensitiveText("a1!bbbbbbbbbb"))
        assertFalse(SuggestionHeuristics.isLikelySensitiveText("normal words here"))
    }

    @Test
    fun urlsAreNeverSensitive() {
        assertFalse(SuggestionHeuristics.isLikelySensitiveText("https://example.com/password: x"))
        assertFalse(SuggestionHeuristics.isLikelySensitiveText("somefile://a1!bbbbbbbbbb"))
    }

    @Test
    fun numbersAfterQuantityWords() {
        assertEquals(listOf("5", "10"), SuggestionHeuristics.numbersForContext(listOf("payment", "in"), ""))
        assertEquals(listOf("20"), SuggestionHeuristics.numbersForContext(listOf("it"), "2"))
        assertEquals(listOf("$"), SuggestionHeuristics.numbersForContext(listOf("it"), ""))
    }

    @Test
    fun numbersRespectDigitPrefix() {
        assertEquals(emptyList<String>(), SuggestionHeuristics.numbersForContext(listOf("in"), "x"))
        assertEquals(emptyList<String>(), SuggestionHeuristics.numbersForContext(emptyList(), ""))
        assertEquals(emptyList<String>(), SuggestionHeuristics.numbersForContext(listOf("banana"), ""))
    }
}
