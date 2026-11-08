package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymSpellIndexTest {
    private val words = listOf("the", "hello", "help", "world", "apple")
    private val index = SymSpellIndex(words)

    private fun SymSpellIndex.names(ids: Set<Int>) = ids.map { wordAt(it) }.toSet()

    @Test fun exposesVocabularyAndRank() {
        assertEquals(5, index.size)
        assertEquals("world", index.wordAt(3))
        assertEquals(3, index.rankOf(3))
    }

    @Test fun findsExactWord() {
        assertTrue(index.names(index.similar("apple")).contains("apple"))
        assertTrue(index.names(index.similarNear("apple")).contains("apple"))
    }

    @Test fun findsOneMissingLetterTypo() {
        assertTrue(index.names(index.similar("helo")).contains("hello"))
        assertTrue(index.names(index.similarNear("helo")).contains("hello"))
    }

    @Test fun findsOneExtraLetterTypo() {
        assertTrue(index.names(index.similar("helllo")).contains("hello"))
    }

    @Test fun doesNotReturnUnrelatedWords() {
        assertFalse(index.names(index.similar("zzzz")).contains("hello"))
    }

    @Test fun emptyAndOverlongQueriesReturnNothing() {
        assertTrue(index.similar("").isEmpty())
        assertTrue(index.similarNear("").isEmpty())
        assertTrue(index.similar("a".repeat(30)).isEmpty())
    }

    @Test fun emptyVocabularyIsSafe() {
        val empty = SymSpellIndex(emptyList())
        assertEquals(0, empty.size)
        assertTrue(empty.similar("abc").isEmpty())
    }
}
