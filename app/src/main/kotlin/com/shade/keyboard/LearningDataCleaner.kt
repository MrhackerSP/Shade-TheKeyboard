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

        // The service's live WordDictionary + BigramModel hold their data in memory
        // with debounced savers — deleting the files underneath them would only last
        // until the next save rewrote the old state. Clear through the live instance
        // when the keyboard has started in this process; otherwise the file deletes
        // below are sufficient (nothing is holding the data).
        val dict = WordDictionary.live
        if (dict != null) {
            dict.clearLearnedInPlace()
            dict.bigramModel?.clear()
            // Belt and braces: if the model is still loading in the background it
            // hasn't received the clear() yet — removing the file makes its late
            // load start from scratch instead of resurrecting the old stats.
            try { java.io.File(ctx.filesDir, "ngram.json").delete() } catch (_: Exception) {}
        } else {
            WordDictionary.clearLearned(ctx)
            try { java.io.File(ctx.filesDir, "ngram.json").delete() } catch (_: Exception) {}
        }
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
        // The service's learned-word map must re-read the restored file even if it
        // still holds unsaved pre-restore changes — the restore is the truth.
        WordDictionary.live?.reloadUserForce()
    }
}
