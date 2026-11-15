package com.shade.keyboard

import com.shade.keyboard.engine.RankingFeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class RankingFeatureExtractorContractTest {
    @Test
    fun featureSizeIsStable() {
        assertEquals(83, RankingFeatureExtractor.FEATURE_SIZE)
    }
}
