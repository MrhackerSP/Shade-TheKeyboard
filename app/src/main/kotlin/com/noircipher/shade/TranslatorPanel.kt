package com.noircipher.shade

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.noircipher.shade.translate.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// TranslatorPanel — the inline-translation overlay shown above the keyboard.
//
// When the user taps the Translate toolbar button, this panel replaces the
// suggestion strip. The user types on the still-visible keyboard; the input
// echoes live in the "source" row, and a debounced async translation appears
// in the "result" row. ENTER commits the translation; backspace edits; the
// target language is selectable via a horizontal chip row.
//
// This fixes the bug where tapping Translate showed no visible UI — the old
// code set translatorActive=true and hid the strip, but never showed any view
// to echo the input or preview the translation.
// ============================================================================

class TranslatorPanel(
    context: Context,
    private val scope: CoroutineScope,
    private val onCommit: (String) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private var theme: KbTheme = Themes.LIGHT

    // Source text (what the user is typing)
    private val sourceText = StringBuilder()

    // Currently-selected target language code
    var target: String = "en"
        private set

    // The last translation result (cached so ENTER can commit without re-fetching)
    private var lastResult: String? = null

    // Debounced translation job
    private var translateJob: Job? = null

    // UI elements
    private val sourceView: TextView
    private val resultView: TextView
    private val targetChips: LinearLayout
    private val targetScroll: HorizontalScrollView

    // Available target languages. Extend as needed — these cover the most common
    // translation targets for Shade's current language set (en, hi, hinglish).
    private val targets = listOf(
        "en" to "English",
        "hi" to "हिन्दी",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "ar" to "العربية",
        "pt" to "Português",
        "ru" to "Русский",
        "ja" to "日本語",
        "zh-CN" to "中文"
    )

    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        orientation = VERTICAL
        setPadding(dp(12f), dp(8f), dp(12f), dp(10f))

        // Source row: "Type to translate…" hint + live echo
        sourceView = TextView(context).apply {
            textSize = 15f
            setTextColor(theme.text)
            setHintTextColor(theme.hint)
            hint = context.getString(R.string.translator_hint)
            minLines = 1
            maxLines = 3
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        addView(sourceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Result row: translation preview (bold, accent-colored)
        resultView = TextView(context).apply {
            textSize = 16f
            setTextColor(theme.accent)
            setHintTextColor(theme.hint)
            hint = context.getString(R.string.translator_loading)
            minLines = 1
            maxLines = 3
            setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
            visibility = View.GONE
        }
        addView(resultView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Target language chip row (horizontal scroll)
        targetScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            targetChips = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(dp(0f), dp(4f), dp(0f), dp(0f))
            }
            addView(targetChips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(targetScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
        })

        buildTargetChips()
    }

    private fun buildTargetChips() {
        targetChips.removeAllViews()
        for ((code, label) in targets) {
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8f)
                layoutParams = lp
                isClickable = true
                isFocusable = true
                val isSelected = code == target
                applyChipStyle(this, isSelected)
                setOnClickListener {
                    target = code
                    // Rebuild chips to update selection highlight
                    buildTargetChips()
                    // Re-translate with the new target
                    translateNow()
                }
            }
            targetChips.addView(chip)
        }
    }

    private fun applyChipStyle(tv: TextView, selected: Boolean) {
        if (selected) {
            tv.background = GradientDrawableCache.forKey(theme.accent, dp(14f).toFloat())
            tv.setTextColor(theme.accentText)
        } else {
            tv.background = GradientDrawableCache.forKey(theme.key, dp(14f).toFloat())
            tv.setTextColor(theme.text)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setTheme(t: KbTheme) {
        theme = t
        sourceView.setTextColor(t.text)
        sourceView.setHintTextColor(t.hint)
        resultView.setTextColor(t.accent)
        resultView.setHintTextColor(t.hint)
        buildTargetChips()
        invalidate()
    }

    /** Append a character (from onText). */
    fun append(text: String) {
        sourceText.append(text)
        updateSource()
        scheduleTranslate()
    }

    /** Append a space (from SPACE key). */
    fun appendSpace() {
        sourceText.append(' ')
        updateSource()
        scheduleTranslate()
    }

    /** Delete the last character (from DELETE key). */
    fun backspace() {
        if (sourceText.isNotEmpty()) {
            sourceText.deleteCharAt(sourceText.length - 1)
            updateSource()
            scheduleTranslate()
        }
    }

    /** Commit the translation and close. Called on ENTER. */
    fun commit() {
        val query = sourceText.toString().trim()
        if (query.isEmpty()) {
            onClose()
            return
        }
        val result = lastResult
        if (result != null) {
            onCommit(result)
        } else {
            // No cached result yet — translate synchronously then commit
            panelScope.launch {
                val translated = withContext(Dispatchers.Default) {
                    TranslationService.translate(query, "auto", target)
                }
                if (translated != null) {
                    onCommit(translated)
                } else {
                    // Fallback: commit the raw source text
                    onCommit(query)
                }
            }
        }
    }

    /** Reset state for a fresh session. */
    fun reset() {
        sourceText.clear()
        lastResult = null
        translateJob?.cancel()
        translateJob = null
        updateSource()
        resultView.visibility = View.GONE
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun updateSource() {
        val text = sourceText.toString()
        if (text.isEmpty()) {
            sourceView.text = null
            sourceView.hint = context.getString(R.string.translator_hint)
            resultView.visibility = View.GONE
            lastResult = null
        } else {
            // Bold the source text for readability
            val ssb = SpannableStringBuilder(text)
            ssb.setSpan(StyleSpan(Typeface.NORMAL), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sourceView.text = ssb
        }
    }

    private fun scheduleTranslate() {
        val query = sourceText.toString().trim()
        if (query.isEmpty()) {
            lastResult = null
            resultView.visibility = View.GONE
            return
        }
        translateJob?.cancel()
        translateJob = panelScope.launch {
            // Debounce: wait 300ms after the last keystroke before translating.
            // This avoids hammering the API on every character.
            delay(300)
            if (!isActive) return@launch
            resultView.visibility = View.VISIBLE
            resultView.text = context.getString(R.string.translator_loading)
            val result = withContext(Dispatchers.Default) {
                TranslationService.translate(query, "auto", target)
            }
            if (!isActive) return@launch
            if (result != null && result.isNotEmpty()) {
                lastResult = result
                resultView.text = result
            } else {
                lastResult = null
                resultView.text = context.getString(R.string.translator_error)
            }
        }
    }

    /** Force a re-translation with the current source (used when target changes). */
    private fun translateNow() {
        val query = sourceText.toString().trim()
        if (query.isEmpty()) return
        scheduleTranslate()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        translateJob?.cancel()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
