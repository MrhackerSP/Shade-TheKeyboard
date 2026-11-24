package com.shade.keyboard

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
import com.shade.keyboard.gif.GifController
import com.shade.keyboard.translate.TranslationService
import com.shade.keyboard.gif.GifItem
import com.shade.keyboard.gif.GifConfig
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.shade.keyboard.ai.NeuralPredictionEngine
import com.shade.keyboard.analytics.TouchBiasModel
import com.shade.keyboard.analytics.TypingStatsStore
import com.shade.keyboard.emoji.EmojiPredictionEngine
import com.shade.keyboard.engine.GrammarEngine
import com.shade.keyboard.engine.HeuristicSuggestionEngine
import com.shade.keyboard.engine.CandidateRanker
import com.shade.keyboard.engine.NeuralSpellChecker
import com.shade.keyboard.engine.TfliteCandidateRankingScorer
import com.shade.keyboard.engine.TfliteContextEncoder
import com.shade.keyboard.engine.TfliteNextWordScorer
import com.shade.keyboard.engine.EmojiPredictor
import com.shade.keyboard.engine.LanguageModelEngine
import com.shade.keyboard.engine.UserWordMemory
import com.shade.keyboard.engine.AdaptiveWordMemory
import com.shade.keyboard.engine.AppContextModel
import com.shade.keyboard.engine.AutocorrectSuppressor
import com.shade.keyboard.engine.ConfidenceCalibrator
import com.shade.keyboard.engine.CorrectionConfidence
import com.shade.keyboard.engine.OnlineRankerTrainer
import com.shade.keyboard.engine.RankingFeatureExtractor
import com.shade.keyboard.engine.RewriteModelManager
import com.shade.keyboard.engine.SessionBigramBoost
import com.shade.keyboard.engine.SentencePositionScorer
import com.shade.keyboard.engine.SuggestionRejectionTracker
import com.shade.keyboard.engine.AcceptanceRateAdapter
import com.shade.keyboard.engine.PerAppVocabularySnapshot
import com.shade.keyboard.motion.ShadeMotion
import com.shade.keyboard.slide.SlideDecoder
import com.shade.keyboard.voice.VoiceInputController
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

    companion object {
        /** The picked background photo, written atomically by SettingsActivity. */
        internal const val BG_IMAGE_FILE = "keyboard_background.jpg"
        /** Pre-port legacy background file name (adopted once, then ignored). */
        internal const val LEGACY_BG_FILE = "bg.jpg"
        /** Timestamp pref bumping whenever the background image changes; 0 = none. */
        internal const val BG_VERSION_PREF = "bg_image_version"
    }

    internal fun applyPrefs() { with(svc) {
        if (!svc.kbReady()) return
        // Runtime GIF API key: the Settings-app dialog writes the same pref as the
        // GIF panel's setup card; refresh here so a key pasted in Settings reaches
        // the running IME on the next prefs application (window show / input start).
        GifConfig.applyPrefs(prefs)
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
        com.shade.keyboard.engine.ProfanityFilter.enabled = blockOffensiveOn
        if (!offensiveListLoaded) {
            offensiveListLoaded = true
            scope.launch(Dispatchers.Default) {
                com.shade.keyboard.engine.AssetTsv.use(
                    svc, "dicts/en_offensive.txt", "ShadeService", Unit,
                    failureMessage = "offensive word list unavailable"
                ) { com.shade.keyboard.engine.ProfanityFilter.load(it) }
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
        // Press-effect pipeline: the engine reads "haptic" / "sound" /
        // "vibration_duration" itself (and listens for pref changes live), so
        // no strength seeding happens here — the slider's default state is the
        // engine's "System default" (the OS keyboard tap).
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.bgBitmap = bgBmp

        // "Colors & background": background image placement,
        // dim, key opacity and the see-through sheet.
        kb.bgFit = when (prefs.getString("photo_fit", "cover")) {
            "fit" -> BackgroundMath.Fit.FIT
            "stretch" -> BackgroundMath.Fit.STRETCH
            else -> BackgroundMath.Fit.COVER
        }
        kb.bgZoom = prefs.getInt("photo_zoom", 100) / 100f
        kb.bgWidthF = prefs.getInt("photo_width", 100) / 100f
        kb.bgHeightF = prefs.getInt("photo_height", 100) / 100f
        kb.bgPanX = prefs.getInt("photo_x", 0) / 100f
        kb.bgPanY = prefs.getInt("photo_y", 0) / 100f
        kb.bgDimAlpha = BackgroundMath.dimAlpha(prefs.getInt("bg_dim", 35))
        kb.bgKeyAlpha = BackgroundMath.keyAlpha(prefs.getInt("key_opacity", 100))
        kb.bgTransparency = prefs.getInt("bg_transparency", 0)

        val side = when (prefs.getString("one_handed", "off")) {
            "left" -> 1; "right" -> 2; else -> 0
        }
        kb.setOneHandedAnimated(side)

        (kb.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (emoji.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }
        (clipPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = contentH }

        emoji.setTheme(kbTheme)
        clipPanel.setTheme(kbTheme)
        if (svc.gifControllerReady()) gifController.panel.setTheme(kbTheme)
        svc.translateBar?.setTheme(kbTheme)

        root.setBackgroundColor(BackgroundMath.bgArgb(kbTheme.bg, prefs.getInt("bg_transparency", 0)))
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
        // Versioned background image: the settings screen
        // writes filesDir/keyboard_background.jpg atomically and stamps
        // `bg_image_version` with a timestamp; the cache here invalidates on
        // version change (the old lastModified stamp needed a file probe on
        // every apply). The legacy bg.jpg path is adopted once so any image
        // a user placed there survives the pipeline swap.
        var version = prefs.getLong("bg_image_version", 0L)
        if (version == 0L) {
            val legacy = File(filesDir, LEGACY_BG_FILE)
            if (legacy.exists()) {
                if (legacy.renameTo(File(filesDir, BG_IMAGE_FILE))) {
                    version = legacy.lastModified()
                    prefs.edit().putLong("bg_image_version", version).apply()
                }
            }
        }
        if (version == 0L) { bgBmp = null; bgStamp = -1L; return }
        if (version == bgStamp && bgBmp != null) return
        try {
            val f = File(filesDir, BG_IMAGE_FILE)
            if (!f.exists()) { bgBmp = null; bgStamp = -1L; return }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val o2 = BitmapFactory.Options().apply {
                inSampleSize = BackgroundMath.decodeSampleSize(o.outWidth, o.outHeight)
            }
            var bmp: Bitmap? = BitmapFactory.decodeFile(f.path, o2)
            // The photo layer can be blurred on the GPU (API 31+); bake the
            // blur into the bitmap instead — it only recomputes when the
            // image or the slider changes.
            val blur = prefs.getInt("bg_blur", 0)
            if (bmp != null && blur > 0) bmp = blurBitmap(bmp, blur)
            bgBmp = bmp
            bgStamp = version
        } catch (t: Exception) { ShadeLog.w("ShadeService", "background image decode failed", t, once = true); bgBmp = null }
    }}

    /** CPU box blur over the decoded pixels (the photo blur knob). */
    private fun blurBitmap(bmp: Bitmap, blurDp: Int): Bitmap? = try {
        val screenW = svc.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        // The blur applies at view scale (dp); approximate the
        // same visual strength by scaling the radius to the bitmap's width.
        val radius = ((blurDp.toFloat() * svc.resources.displayMetrics.density) *
            bmp.width.toFloat() / screenW).toInt().coerceIn(1, 24)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val blurred = BackgroundMath.boxBlurPx(px, bmp.width, bmp.height, radius)
        Bitmap.createBitmap(blurred, bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        ShadeLog.w("ShadeService", "background blur failed", t, once = true)
        null
    } ?: bmp

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