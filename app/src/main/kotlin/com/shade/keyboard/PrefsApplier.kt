package com.shade.keyboard

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shade.keyboard.gif.GifConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies persisted preferences and window/geometry state to the running
 * keyboard. Pure applier — no typing logic. Extracted from ShadeService (v11).
 */
internal class PrefsApplier(private val svc: ShadeService) {
    companion object {
        internal const val BG_IMAGE_FILE = "keyboard_background.jpg"
        internal const val LEGACY_BG_FILE = "bg.jpg"
        internal const val BG_VERSION_PREF = "bg_image_version"
    }

    internal fun applyPrefs() { with(svc) {
        if (!svc.kbReady()) return
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
        kb.showPreview = prefs.getBoolean("popup_preview", true)
        kb.bgBitmap = bgBmp
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
        keyboardOffsetY = PrefsMigration.readOffsetY(prefs)
        loadSideInsets()
        root.post { applyKeyboardOffset() }
        applyMode(mode, force = true)
    }}

    internal fun loadBackground() { with(svc) {
        var version = prefs.getLong(BG_VERSION_PREF, 0L)
        if (version == 0L) {
            val legacy = File(filesDir, LEGACY_BG_FILE)
            if (legacy.exists()) {
                if (legacy.renameTo(File(filesDir, BG_IMAGE_FILE))) {
                    version = legacy.lastModified()
                    prefs.edit().putLong(BG_VERSION_PREF, version).apply()
                }
            }
        }
        if (version == 0L) { bgBmp = null; bgStamp = -1L; return }
        // Cache key includes the blur slider: blur is baked into the bitmap, so a
        // blur change must invalidate exactly like an image change does.
        val blur = prefs.getInt("bg_blur", 0)
        val cacheKey = version * 31L + blur
        if (cacheKey == bgStamp && bgBmp != null) return
        bgStamp = cacheKey // claim now so concurrent applies don't stack decodes
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(filesDir, BG_IMAGE_FILE)
                if (!f.exists()) {
                    withContext(Dispatchers.Main) { bgBmp = null; bgStamp = -1L; kb.bgBitmap = null }
                    return@launch
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                val o2 = BitmapFactory.Options().apply {
                    inSampleSize = BackgroundMath.decodeSampleSize(o.outWidth, o.outHeight)
                }
                var bmp: Bitmap? = BitmapFactory.decodeFile(f.path, o2)
                if (bmp != null && blur > 0) bmp = blurBitmap(bmp, blur)
                val decoded = bmp
                withContext(Dispatchers.Main) {
                    if (bgStamp != cacheKey) return@withContext // a newer apply won
                    bgBmp = decoded
                    if (svc.kbReady()) {
                        kb.bgBitmap = decoded
                        kb.invalidate()
                    }
                }
            } catch (t: Exception) {
                ShadeLog.w("ShadeService", "background image decode failed", t, once = true)
            }
        }
    }}

    /** CPU box blur over the decoded pixels (the photo blur knob). */
    private fun blurBitmap(bmp: Bitmap, blurDp: Int): Bitmap? = try {
        val screenW = svc.resources.displayMetrics.widthPixels.coerceAtLeast(1)
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

    internal fun applyKeyboardOffset() { with(svc) {
        if (!svc.rootReady()) return
        root.translationY = -keyboardOffsetY
        if (svc.headroomReady()) {
            val want = if (keyboardOffsetY == 0f) View.VISIBLE else View.GONE
            if (headroom.visibility != want) headroom.visibility = want
        }
    }}

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
        // The strip lives inside the fixed-height stripSlot (a FrameLayout) since the
        // panel-header height-jitter fix, so its params are FrameLayout's.
        val lp = strip.layoutParams as? FrameLayout.LayoutParams ?: return
        when (kb.oneHanded) {
            1 -> { lp.width = availW.toInt(); lp.gravity = Gravity.START }
            2 -> { lp.width = availW.toInt(); lp.gravity = Gravity.END }
            else -> { lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.gravity = Gravity.START }
        }
        strip.layoutParams = lp
        strip.requestLayout()
    }}
}
