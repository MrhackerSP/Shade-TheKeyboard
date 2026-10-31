package com.noircipher.shade

import android.content.Context
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.ConfidenceCalibrator
import com.noircipher.shade.engine.OnlineRankerTrainer

object LearningDataCleaner {

    val ADAPTIVE_PREFS_FILES = listOf(
        "shade_adaptive_words",
        "shade_app_context",
        "shade_autocorrect_suppressor",
        "shade_confidence_calibrator",
        "shade_online_ranker",
    )

    fun clearAll(ctx: Context) {
        AdaptiveWordMemory(ctx).clear()
        AppContextModel(ctx).clear()
        AutocorrectSuppressor(ctx).clear()
        ConfidenceCalibrator(ctx).clear()
        OnlineRankerTrainer(ctx).clear()
    }

    fun clearOne(ctx: Context, prefsFile: String) {
        when (prefsFile) {
            "shade_adaptive_words" -> AdaptiveWordMemory(ctx).clear()
            "shade_app_context" -> AppContextModel(ctx).clear()
            "shade_autocorrect_suppressor" -> AutocorrectSuppressor(ctx).clear()
            "shade_confidence_calibrator" -> ConfidenceCalibrator(ctx).clear()
            "shade_online_ranker" -> OnlineRankerTrainer(ctx).clear()
        }
    }
}
