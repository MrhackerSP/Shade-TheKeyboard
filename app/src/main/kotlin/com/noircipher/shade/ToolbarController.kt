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
 * Owns the toolbar row: rebuild, refresh, toggle animation, inline-autofill chips and the clipboard chip. Extracted from ShadeService (v11 refactor).
 *
 * Every method body runs inside `with(svc)` so member references
 * resolve exactly as they did when these were members of
 * ShadeService - locals shadow identically and `with` being inline
 * preserves bare-return control flow. Behavior is unchanged.
 */
internal class ToolbarController(private val svc: ShadeService) {

    internal fun rebuildToolbar() { with(svc) {
        toolRow.removeAllViews()
        val enabled = prefs.getStringSet("toolbar_keys", null) ?: toolDefs.map { it.first }.toSet()
        for ((id, label) in toolDefs) {
            if (enabled.contains(id) == false) continue
            if (id == "gif" && !gifAvailable()) continue
            val tv = ImageView(this).apply {
                setImageResource(toolIcons[id] ?: R.drawable.ic_tb_settings)
                setColorFilter(if (id == "incognito" && incognito) kbTheme.accent else kbTheme.text)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt())
                contentDescription = label
                isClickable = true
                isFocusable = false
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().cancel()
                            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(ShadeMotion.KEY_PRESS_DOWN_MS)
                                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                                .start()
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().cancel()
                            v.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(ShadeMotion.KEY_PRESS_UP_MS)
                                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                                .start()
                            false
                        }
                        else -> false
                    }
                }
                setOnClickListener { onTool(id) }
            }
            if (id == "incognito" && incognito) {
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(kbTheme.keyFn)
                }
            }
            toolRow.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
    }}

    internal fun refreshStrip() { with(svc) {
        if (!svc.stripReady()) return
        val suggestionsActive = suggestionsOn && suggestionsAllowed
        val showTools = toolbarOpen || (suggestionsActive == false)

        slotFrame.visibility = if (showTools) View.GONE else View.VISIBLE
        toolScroll.visibility = if (showTools) View.VISIBLE else View.GONE

        slotFrame.alpha = 0f
        toolScroll.alpha = 0f
        val target = if (showTools) toolScroll else slotFrame
        target.animate()
            .alpha(1f)
            .setDuration(ShadeMotion.CANDIDATE_SWAP_MS)
            .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
            .start()

        toggle.text = if (showTools) "‹" else "›"
        toggle.visibility = if (suggestionsActive) View.VISIBLE else View.GONE
        strip.visibility = View.VISIBLE
    }}

    internal fun animateToggle() { with(svc) {
        toggle.animate().cancel()
        toggle.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(ShadeMotion.UI_TOGGLE_IN_MS)
                .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
            .withEndAction {
                toggle.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(ShadeMotion.UI_TOGGLE_OUT_MS)
                    .setInterpolator(ShadeMotion.FAST_OUT_SLOW_IN)
                    .start()
            }
            .start()
    }}

    internal fun updateAutofillStrip() { with(svc) {
        if (!svc.autofillScrollReady()) return
        val show = autofillCount > 0 && !autofillHiddenByTyping
        if (show) autofillScroll.setBackgroundColor(kbTheme.bg)
        autofillScroll.visibility = if (show) View.VISIBLE else View.GONE
    }}

    internal fun clearAutofillChips() { with(svc) {
        autofillGen++
        autofillCount = 0
        autofillHiddenByTyping = false
        if (svc.autofillRowReady()) autofillRow.removeAllViews()
        updateAutofillStrip()
    }}

    /**
     * Refreshes [lastClipText] from the clipboard store. Called from [captureClip]
     * after a new clip is added, and from [onStartInputView] so the chip is
     * available when the keyboard appears. Only short text clips (< 30 chars)
     * qualify; image clips and longer text are ignored for the chip.
     */
    internal fun maybeShowClipboardChip() { with(svc) {
        if (!prefs.getBoolean("clipboard_history", true) || incognito ||
            fieldNoPersonalizedLearning || fieldSecureInput
        ) {
            lastClipText = null
            return
        }
        try {
            val clip = clips.load().firstOrNull()
            if (clip != null && clip.text.isNotEmpty() && clip.text.length < 30) {
                lastClipText = clip.text
            } else {
                lastClipText = null
            }
        } catch (t: Exception) {
            ShadeLog.w("ShadeService", "clipboard preload failed", t, once = true)
            lastClipText = null
        }
    }}

}