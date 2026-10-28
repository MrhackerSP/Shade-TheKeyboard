package com.noircipher.shade

import com.noircipher.shade.emoji.EmojiPredictionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPredictionEngineTest {
    @Test
    fun exactKeywordReturnsEmoji() {
        val engine = EmojiPredictionEngine.builtIn()
        assertEquals(listOf("😊", "😄", "😁"), engine.predictEmoji("happy", 3))
    }

    @Test
    fun prefixSearchFindsEmoji() {
        val engine = EmojiPredictionEngine.builtIn()
        val results = engine.predictEmoji("hap", 3)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun shortPrefixReturnsEmpty() {
        val engine = EmojiPredictionEngine.builtIn()
        assertTrue(engine.predictEmoji("h", 3).isEmpty())
    }
}
