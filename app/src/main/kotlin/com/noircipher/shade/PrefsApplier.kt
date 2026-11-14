package com.noircipher.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import com.noircipher.shade.gif.GifController
import com.noircipher.shade.translate.TranslationService
import com.noircipher.shade.gif.GifItem
import com.noircipher.shade.gif.KlipyConfig
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.noircipher.shade.ai.NeuralPredictionEngine
import com.noircipher.shade.analytics.TouchBiasModel
import com.noircipher.shade.analytics.TypingStatsStore
import com.noircipher.shade.emoji.EmojiPredictionEngine
import com.noircipher.shade.engine.GrammarEngine
import com.noircipher.shade.engine.HeuristicSuggestionEngine
import com.noircipher.shade.engine.CandidateRanker
import com.noircipher.shade.engine.NeuralSpellChecker
import com.noircipher.shade.engine.TfliteCandidateRankingScorer
import com.noircipher.shade.engine.TfliteContextEncoder
import com.noircipher.shade.engine.TfliteNextWordScorer
import com.noircipher.shade.engine.EmojiPredictor
import com.noircipher.shade.engine.LanguageModelEngine
import com.noircipher.shade.engine.UserWordMemory
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.ConfidenceCalibrator
import com.noircipher.shade.engine.CorrectionConfidence
import com.noircipher.shade.engine.OnlineRankerTrainer
import com.noircipher.shade.engine.RankingFeatureExtractor
import com.noircipher.shade.engine.RewriteModelManager
import com.noircipher.shade.engine.SessionBigramBoost
import com.noircipher.shade.engine.SentencePositionScorer
import com.noircipher.shade.engine.SuggestionRejectionTracker
import com.noircipher.shade.engine.AcceptanceRateAdapter
import com.noircipher.shade.engine.PerAppVocabularySnapshot
import com.noircipher.shade.motion.ShadeMotion
import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.voice.VoiceInputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.ArrayDeque

/**
 * Applies persisted preferences and window/geometry state to the running keyboard: theme assets, key height, floating offset, one-handed strip transform, side insets. Pure applier - no typing logic. Extracted from ShadeService (v11 refactor).
 *
 * Every method body runs inside `with(svc)` so member references
 * resolve exactly as they did when these were members of
 * ShadeService - locals shadow identically and `with` being inline
 * preserves bare-return control flow. Behavior is unchanged.
 */
internal class PrefsApplier(private val svc: ShadeService) {

    internal fun applyPrefs() { with(svc) {
        if (!svc.kbReady()) return
        kbTheme = Themes.resolve(this, prefs)
        updateWallpaperListener()
        numberRow = prefs.getBoolean("number_row", false)
        suggestionsOn = prefs.getBoolean("suggestions", true)
        typingInsightsOn = prefs.getBoolean("typing_insights", false)
        neuralOn = prefs.getBoolean("neural_predictions", true)
        neural?.enabled = neuralOn
        learnOn = prefs.getBoolean("learn_words", true)
        learningAdaptiveOn = prefs.getBoolean("learning_adaptive", true)
        learningAppCtxOn = prefs.getBoolean("learning_appctx", true)
        learningSuppressorOn = prefs.getBoolean("learning_suppressor", true)
        learningCalibratorOn = prefs.getBoolean("learning_calibrator", true)
        learningRankerOn = prefs.getBoolean("learning_ranker", true)
        learningCorrectionOn = prefs.getBoolean("learning_correction", true)
        learningTouchOn = prefs.getBoolean("learning_touch", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpace = prefs.getBoolean("double_space", true)
        autocorrectOn = prefs.getBoolean("autocorrect", true)
        blockOffensiveOn = prefs.getBoolean("block_offensive", true)
        com.noircipher.shade.engine.ProfanityFilter.enabled = blockOffensiveOn
        if (!offensiveListLoaded) {
            offensiveListLoaded = true
            scope.launch(Dispatchers.Default) {
                com.noircipher.shade.engine.AssetTsv.use(
                    svc, "dicts/en_offensive.txt", "ShadeService", Unit,
                    failureMessage = "offensive word list unavailable"
                ) { com.noircipher.shade.engine.ProfanityFilter.load(it) }
            }
        }
        smartCorrectionOn = prefs.getBoolean("smart_correction", true)
        emojiSuggest = prefs.getBoolean("emoji_suggestions", true)
        phraseSuggestionsOn = prefs.getBoolean("phrase_suggestions", false)
        incognito = prefs.getBoolean("incognito", false) || fieldNoPersonalizedLearning || fieldSecureInput
        layoutName = prefs.getString("layout", "qwerty") ?: "qwerty"
        kb.trackpadEnabled = prefs.getBoolean("trackpad_mode", true)
        kb.glideEnabled = prefs.getBoolean("glide_typing", true) && !fieldSecureInput
        kb.typingBurstEnabled = prefs.getBoolean("typing_burst", false)
        kb.longPressMs = prefs.getInt("longpress_delay", 300).toLong()
        kb.repeatDelayMs = prefs.getInt("repeat_delay", 350).toLong()
        kb.repeatIntervalMs = prefs.getInt("repeat_interval", 40).toLong()

        val locale = dictIds()
        scope.launch(Dispatchers.Default) {
            try { configureLocaleAssets(locale) } catch (t: Throwable) {
                Log.w("ShadeService", "configureLocaleAssets failed", t)
            }
        }

        val keyH = prefs.getInt("key_height", 50)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val factor = if (landscape) 0.65f else 1f
        val contentH = (dp(keyH.toFloat()) * (if (numberRow) 4.7f else 4f) * factor).toInt()
        val split = when (prefs.getString("split_keyboard", "off")) {
            "always" -> true
            "landscape" -> landscape
            else -> false
        }
        loadBackground()

        kb.kbTheme = kbTheme
        kb.contentHeightPx = contentH
        kb.setSplitAnimated(split)
        kb.keyBorders = prefs.getBoolean("key_borders", true)
        kb.showHints = prefs.getBoolean("show_hints", true)
        kb.haptic = prefs.getBoolean("haptic", true)
        kb.hapticStrength = when (prefs.getString("haptic_strength", "medium")) {
            "light" -> 0
            "strong" -> 2
            else -> 1
        }
        kb.sound = prefs.getBoolean("sound", false)
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.bgBitmap = bgBmp

        val side = when (prefs.getString("one_handed", "off")) {
            "left" -> 1; "right" -> 2; else -> 0
        }
        kb.setOneHandedAnimated(side)

        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }

        emoji.setTheme(kbTheme)
        clipPanel.setTheme(kbTheme)
        if (svc.gifControllerReady()) gifController.bar.setTheme(kbTheme)

        root.setBackgroundColor(kbTheme.bg)
        toggle.setTextColor(kbTheme.hint)
        for (tv in slots) tv.setTextColor(kbTheme.text)
        for (tv in slotOlds) tv.setTextColor(kbTheme.text)
        slotRow.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        slotRow.dividerPadding = dp(14f).toInt()
        slotRow.dividerDrawable = GradientDrawable().apply {
            setSize(dp(1f).toInt(), 0)
            setColor((kbTheme.hint and 0x00FFFFFF) or 0x55000000)
        }
        svc.toolbarC.rebuildToolbar()
        svc.toolbarC.refreshStrip()
        root.post { applyOneHandedToStrip() }

        // Restore the saved floating offset (if the user previously undocked
        // the keyboard). Called after layout so translationY is applied to
        // the final measured view.
        keyboardOffsetY = PrefsMigration.readOffsetY(prefs)
        loadSideInsets()
        root.post { applyKeyboardOffset() }

        applyMode(mode, force = true)
    }}

    internal fun loadBackground() { with(svc) {
        val f = File(filesDir, "bg.jpg")
        if (f.exists() == false) { bgBmp = null; bgStamp = -1L; return }
        if (f.lastModified() == bgStamp && bgBmp != null) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / sample > 1400) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            bgBmp = BitmapFactory.decodeFile(f.path, o2)
            bgStamp = f.lastModified()
        } catch (t: Exception) { ShadeLog.w("ShadeService", "background image decode failed", t, once = true); bgBmp = null }
    }}

    /** Applies the current [keyboardOffsetY] as a translation on the root view. */
    internal fun applyKeyboardOffset() { with(svc) {
        if (!svc.rootReady()) return
        // Negative translationY moves the view UP, so we negate the offset.
        root.translationY = -keyboardOffsetY
        // A floating (offset) keyboard has no room-above semantics; drop the band so it can't
        // end up covering the keyboard's touchable area.
        if (svc.headroomReady()) {
            val want = if (keyboardOffsetY == 0f) View.VISIBLE else View.GONE
            if (headroom.visibility != want) headroom.visibility = want
        }
    }}

    /**
     * Applies the current `key_height` preference to the keyboard, emoji panel,
     * and clip panel — without the full locale-asset reload that applyPrefs()
     * triggers. Called from the resize drag handler for live resizing.
     */
    internal fun applyKeyHeight() { with(svc) {
        if (!svc.kbReady()) return
        val keyH = prefs.getInt("key_height", 50)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val factor = if (landscape) 0.65f else 1f
        val contentH = (dp(keyH.toFloat()) * (if (numberRow) 4.7f else 4f) * factor).toInt()
        kb.contentHeightPx = contentH
        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        if (svc.emojiReady()) {
            (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        }
        if (svc.clipPanelReady()) {
            (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        }
        kb.requestLayout()
    }}

    internal fun applySideInsets() { with(svc) {
        if (!svc.rootReady()) return
        root.setPadding(kbInsetLeftPx, 0, kbInsetRightPx, navBarBottomPx)
    }}

    internal fun loadSideInsets() { with(svc) {
        kbInsetLeftPx = dp(prefs.getInt("kb_inset_left", 0).toFloat()).toInt()
        kbInsetRightPx = dp(prefs.getInt("kb_inset_right", 0).toFloat()).toInt()
        applySideInsets()
    }}

    internal fun applyOneHandedToStrip() { with(svc) {
        if (!svc.stripReady()) return
        val w = root.width.toFloat()
        if (w <= 0f) return
        val availW = w * 0.75f
        val lp = strip.layoutParams as? LinearLayout.LayoutParams ?: return
        when (kb.oneHanded) {
            1 -> {
                lp.width = availW.toInt()
                lp.gravity = Gravity.START
            }
            2 -> {
                lp.width = availW.toInt()
                lp.gravity = Gravity.END
            }
            else -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.START
            }
        }
        strip.layoutParams = lp
        strip.requestLayout()
    }}

}