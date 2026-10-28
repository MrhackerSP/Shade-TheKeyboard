package com.noircipher.shade

import com.noircipher.shade.engine.RankingFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingFeatureExtractorTest {
    @Test
    fun featureVectorHasExpectedSize() {
        val features = RankingFeatureExtractor.extract(
            candidate = "hello",
            typedPrefix = "he",
            baseRankScore = 1f,
            neuralScore = 0.5f,
            spatialScore = 0.2f,
            touches = emptyList(),
            layout = emptyMap(),
            contextEmbedding = FloatArray(96) { 0.1f },
            userUnigramScore = 0f,
            userBigramScore = 0f
        )
        assertEquals(RankingFeatureExtractor.FEATURE_SIZE, features.size)
        assertTrue(features.all { it.isFinite() })
    }
}
