package com.shade.keyboard

import com.shade.keyboard.engine.RankingFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingFeatureExtractorTest {
    @Test
    fun featureVectorHasExpectedSize() {
        val features = RankingFeatureExtractor.extract(
            candidate = "hello",
            typedWord = "he"
        )
        assertEquals(RankingFeatureExtractor.FEATURE_SIZE, features.size)
        assertTrue(features.all { it.isFinite() })
    }

    @Test
    fun featureVectorIsFiniteForAdversarialInput() {
        // Out-of-table characters, huge counts and out-of-range type codes all
        // degrade gracefully — the vector stays finite and contract-sized.
        val features = RankingFeatureExtractor.extract(
            candidate = "π✨🚀ï♥",
            typedWord = "键盘⌨️",
            typeCode = 999,
            userUnigramCount = 10_000,
            userBigramCount = -10
        )
        assertEquals(RankingFeatureExtractor.FEATURE_SIZE, features.size)
        assertTrue(features.all { it.isFinite() })
    }
}
