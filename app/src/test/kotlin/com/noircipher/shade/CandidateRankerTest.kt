package com.noircipher.shade

import com.noircipher.shade.engine.CandidateRanker
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateRankerTest {
    @Test
    fun mergeNextWordDeduplicatesAndLimits() {
        assertEquals(
            listOf("hello", "hi"),
            CandidateRanker.mergeNextWord(
                neural = listOf("hello", "hi", "hey"),
                dictionary = listOf("hello", "hola"),
                limit = 2
            )
        )
    }

    @Test
    fun mergeNextWordInterleavesSoDictionaryGetsASlot() {
        assertEquals(
            listOf("a", "x", "b"),
            CandidateRanker.mergeNextWord(
                neural = listOf("a", "b", "c", "d"),
                dictionary = listOf("x", "y"),
                limit = 3
            )
        )
    }
}
