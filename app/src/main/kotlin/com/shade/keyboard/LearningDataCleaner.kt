package com.shade.keyboard

import android.content.Context
import com.shade.keyboard.analytics.TouchBiasModel
import com.shade.keyboard.engine.AdaptiveWordMemory
import com.shade.keyboard.engine.AppContextModel
import com.shade.keyboard.engine.AutocorrectSuppressor
import com.shade.keyboard.engine.ConfidenceCalibrator
import com.shade.keyboard.engine.CorrectionMemory
import com.shade.keyboard.engine.OnlineRankerTrainer
import com.shade.keyboard.engine.UserWordMemory

object LearningDataCleaner {

    val ADAPTIVE_PREFS_FILES = listOf(
        "shade_adaptive_words",
        "shade_app_context",
        "shade_autocorrect_suppressor",
        "shade_confidence_calibrator",
        "shade_correction_memory",
        "shade_online_ranker",
        "shade_touch_bias",
    )

    fun clearAll(ctx: Context) {
        LiveStores.get { AdaptiveWordMemory(ctx.applicationContext) }.clear()
        LiveStores.get { AppContextModel(ctx.applicationContext) }.clear()
        LiveStores.get { AutocorrectSuppressor(ctx.applicationContext) }.clear()
        LiveStores.get { ConfidenceCalibrator(ctx.applicationContext) }.clear()
        LiveStores.get { CorrectionMemory(ctx.applicationContext) }.clear()
        LiveStores.get { OnlineRankerTrainer(ctx.applicationContext) }.clear()
        LiveStores.get { TouchBiasModel(ctx.applicationContext) }.clear()
        UserWordMemory(ctx).clear()
    }

    fun clearOne(ctx: Context, prefsFile: String) {
        when (prefsFile) {
            "shade_adaptive_words" -> LiveStores.get { AdaptiveWordMemory(ctx.applicationContext) }.clear()
            "shade_app_context" -> LiveStores.get { AppContextModel(ctx.applicationContext) }.clear()
            "shade_autocorrect_suppressor" -> LiveStores.get { AutocorrectSuppressor(ctx.applicationContext) }.clear()
            "shade_confidence_calibrator" -> LiveStores.get { ConfidenceCalibrator(ctx.applicationContext) }.clear()
            "shade_correction_memory" -> LiveStores.get { CorrectionMemory(ctx.applicationContext) }.clear()
            "shade_online_ranker" -> LiveStores.get { OnlineRankerTrainer(ctx.applicationContext) }.clear()
            "shade_touch_bias" -> LiveStores.get { TouchBiasModel(ctx.applicationContext) }.clear()
        }
    }

    /**
     * Re-loads every adaptive store from disk. Called after a backup restore
     * overwrote the underlying files: the live singletons (LiveStores) hold
     * cached state that would otherwise shadow the restored data until the
     * next process start.
     */
    fun reloadAll(ctx: Context) {
        LiveStores.get { AdaptiveWordMemory(ctx.applicationContext) }.reload()
        LiveStores.get { AppContextModel(ctx.applicationContext) }.reload()
        LiveStores.get { AutocorrectSuppressor(ctx.applicationContext) }.reload()
        LiveStores.get { CorrectionMemory(ctx.applicationContext) }.reload()
        LiveStores.get { OnlineRankerTrainer(ctx.applicationContext) }.reload()
        LiveStores.get { TouchBiasModel(ctx.applicationContext) }.reload()
        UserWordMemory(ctx).reload()
        // ConfidenceCalibrator reads SharedPreferences on every call (no cache)
        // and picks up restored data on its own.
    }
}
