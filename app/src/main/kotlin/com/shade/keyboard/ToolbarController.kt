package com.shade.keyboard

import android.annotation.SuppressLint
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
 * Owns the toolbar row: rebuild, refresh, toggle animation, inline-autofill chips and the clipboard chip. Extracted from ShadeService (v11 refactor).
 *
 * Every method body runs inside `with(svc)` so member references
 * resolve exactly as they did when these were members of
 * ShadeService - locals shadow identically and `with` being inline
 * preserves bare-return control flow. Behavior is unchanged.
 */
// Toolbar icons handle raw touches for press feedback by design.
@SuppressLint("ClickableViewAccessibility")
internal class ToolbarController(private val svc: ShadeService) {

    internal fun rebuildToolbar() { with(svc) {
        toolRow.removeAllViews()
        val enabled = prefs.getStringSet("toolbar_keys", null) ?: toolDefs.map { it.first }.toSet()
        for ((id, label) in toolDefs) {
            if (enabled.contains(id) == false) continue
            // The strip's right-edge voice button already shows the mic — don't
            // repeat it in the row. (Hidden button → voice returns to the row.)
            if (id == "voice" && stripVoiceVisible()) continue
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
        // The strip must never pop back over an open panel (emoji header), the GIF bar or the
        // translator bar. transitionToPanel() hides the strip and then calls refreshStrip(); without
        // this guard the unconditional VISIBLE below resurrected the suggestion bar over the emoji
        // search box. When a full-screen surface owns the strip's place, the strip and its children
        // stay hidden and there is nothing to refresh.
        if (!svc.stripVisibleAllowed()) {
            strip.visibility = View.GONE
            return
        }
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
        // Voice button follows its setting live (PrefsApplier → refreshStrip).
        refreshVoiceStripBtn()
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