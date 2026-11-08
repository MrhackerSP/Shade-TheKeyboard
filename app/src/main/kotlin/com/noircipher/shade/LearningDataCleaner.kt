package com.noircipher.shade

import android.content.Context
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.ConfidenceCalibrator
import com.noircipher.shade.engine.OnlineRankerTrainer
import com.noircipher.shade.engine.UserWordMemory

object LearningDataCleaner {

    val ADAPTIVE_PREFS_FILES = listOf(
        "shade_adaptive_words",
        "shade_app_context",
        "shade_autocorrect_suppressor",
        "shade_confidence_calibrator",
        "shade_online_ranker",
    )

    fun clearAll(ctx: Context) {
        LiveStores.get { AdaptiveWordMemory(ctx.applicationContext) }.clear()
        LiveStores.get { AppContextModel(ctx.applicationContext) }.clear()
        LiveStores.get { AutocorrectSuppressor(ctx.applicationContext) }.clear()
        LiveStores.get { ConfidenceCalibrator(ctx.applicationContext) }.clear()
        LiveStores.get { OnlineRankerTrainer(ctx.applicationContext) }.clear()
        UserWordMemory(ctx).clear()
    }

    fun clearOne(ctx: Context, prefsFile: String) {
        when (prefsFile) {
            "shade_adaptive_words" -> LiveStores.get { AdaptiveWordMemory(ctx.applicationContext) }.clear()
            "shade_app_context" -> LiveStores.get { AppContextModel(ctx.applicationContext) }.clear()
            "shade_autocorrect_suppressor" -> LiveStores.get { AutocorrectSuppressor(ctx.applicationContext) }.clear()
            "shade_confidence_calibrator" -> LiveStores.get { ConfidenceCalibrator(ctx.applicationContext) }.clear()
            "shade_online_ranker" -> LiveStores.get { OnlineRankerTrainer(ctx.applicationContext) }.clear()
        }
    }
}
