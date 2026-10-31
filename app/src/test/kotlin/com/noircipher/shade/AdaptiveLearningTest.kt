package com.noircipher.shade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.OnlineRankerTrainer
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLearningTest {
    @Test
    fun adaptiveStoresBasicBehavior() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val memory = AdaptiveWordMemory(context)
        memory.record("shade")
        assertTrue(memory.score("shade") > 0f)

        val suppressor = AutocorrectSuppressor(context)
        suppressor.recordRejection("the", "teh", "the")
        suppressor.recordRejection("the", "teh", "the")
        assertTrue(suppressor.shouldSuppress("the", "teh", "the"))

        val trainer = OnlineRankerTrainer(context)
        val pos = FloatArray(83) { 0.5f }
        val neg = listOf(FloatArray(83) { -0.5f })
        val before = trainer.score(pos)
        trainer.adapt(pos, neg)
        assertTrue(trainer.score(pos) != before)

        memory.clear()
    }
}
