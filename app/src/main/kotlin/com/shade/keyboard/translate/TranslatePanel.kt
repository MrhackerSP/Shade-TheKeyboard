package com.shade.keyboard.translate

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.shade.keyboard.KbTheme
import com.shade.keyboard.R
import com.shade.keyboard.Themes
import java.util.Locale

/**
 * Translator bar that sits above the keyboard while translating.
 *
 * Layout (top to bottom):
 *  - title row: "Translate" + target-language chip (tap to cycle) + close button
 *  - input display: what the person typed on the keyboard below (keys are forwarded by the
 *    service while the bar is open — this is the "text field" the translate flow was missing)
 *  - result row: the translated text with an Insert button, or a status line
 *    (hint / "Translating…" / error)
 *
 * The panel is display-only: it never opens the system IME, and all state lives in the service.
 */
// Built programmatically with its dependencies; never inflated from XML.
class TranslatePanel(
    context: Context,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onClose()
        /** Insert button (or Enter again) — commits the translation into the app's field. */
        fun onInsert()
        /** The language chip was tapped; the service cycles the target and updates the panel. */
        fun onCycleTarget()
    }

    private var theme: KbTheme = Themes.LIGHT

    private val titleView = TextView(context)
    private val targetChip = TextView(context)
    private val closeBtn = TextView(context)
    private val inputView = TextView(context)
    private val resultView = TextView(context)
    private val insertBtn = TextView(context)

    // Display state (declared BEFORE the init block below — init reads them through
    // setTheme → refreshInput/refreshResult, and Kotlin runs initializers in declaration order).
    private var inputText: String = ""
    private var statusText: String = context.getString(R.string.translator_hint)
    private var resultText: String = ""
    private var hasResult = false

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(4f), dp(2f), dp(4f), dp(4f))

        // --- title row -------------------------------------------------------------------------
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(8f), 0)
        }
        titleView.text = context.getString(R.string.translator_title)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.setPadding(dp(12f), 0, 0, 0)
        top.addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f)))

        targetChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        targetChip.typeface = Typeface.DEFAULT_BOLD
        targetChip.gravity = Gravity.CENTER
        targetChip.setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        targetChip.setOnClickListener { callbacks.onCycleTarget() }
        val chipParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(28f)).apply {
            marginStart = dp(10f)
        }
        top.addView(targetChip, chipParams)

        val spacer = LinearLayout(context)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        closeBtn.text = "✕"
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        closeBtn.contentDescription = context.getString(R.string.action_cancel)
        closeBtn.setOnClickListener { callbacks.onClose() }
        top.addView(closeBtn, LayoutParams(dp(40f), dp(36f)))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // --- input display (the missing "text field") -------------------------------------------
        inputView.maxLines = 1
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputView.gravity = Gravity.CENTER_VERTICAL
        inputView.setPadding(dp(12f), 0, dp(12f), 0)
        addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)).apply {
            marginStart = dp(6f); marginEnd = dp(6f)
        })

        // --- result row --------------------------------------------------------------------------
        val resultRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        resultView.maxLines = 1
        resultView.gravity = Gravity.CENTER_VERTICAL
        resultView.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        resultRow.addView(resultView, LinearLayout.LayoutParams(0, dp(36f), 1f))

        insertBtn.text = context.getString(R.string.translator_insert)
        insertBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        insertBtn.typeface = Typeface.DEFAULT_BOLD
        insertBtn.gravity = Gravity.CENTER
        insertBtn.setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        insertBtn.visibility = GONE
        insertBtn.setOnClickListener { callbacks.onInsert() }
        resultRow.addView(insertBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
            marginStart = dp(8f)
        })
        addView(resultRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setTheme(theme)
        refreshInput()
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        titleView.setTextColor(t.text)
        closeBtn.setTextColor(t.text)
        targetChip.setTextColor(t.text)
        targetChip.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        inputView.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        resultView.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        insertBtn.setTextColor(t.accentText)
        insertBtn.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(t.accent)
        }
        refreshInput()
        refreshResult()
    }

    /** Shows what has been typed so far (keys are forwarded by the service). */
    fun setQuery(q: String) {
        inputText = q
        refreshInput()
    }

    /** Status line when there is no result yet: hint / "Translating…" / error. */
    fun setStatus(text: String) {
        hasResult = false
        insertBtn.visibility = GONE
        statusText = text
        refreshResult()
    }

    /** Shows the translated text together with the Insert button. */
    fun setResult(text: String) {
        hasResult = true
        resultText = text
        insertBtn.visibility = VISIBLE
        refreshResult()
    }

    fun setTarget(code: String) {
        val name = Locale.forLanguageTag(code).displayLanguage
        targetChip.text = "→ $name"
    }

    private fun refreshInput() {
        val sb = SpannableStringBuilder()
        if (inputText.isEmpty()) {
            sb.append(context.getString(R.string.translator_hint))
            sb.setSpan(ForegroundColorSpan(theme.hint), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(inputText)
            sb.setSpan(ForegroundColorSpan(theme.text), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("|")
            sb.setSpan(ForegroundColorSpan(theme.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        inputView.text = sb
    }

    private fun refreshResult() {
        if (hasResult) {
            resultView.text = resultText
        } else {
            resultView.text = statusText
        }
    }
}
