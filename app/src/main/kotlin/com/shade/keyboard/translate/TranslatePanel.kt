package com.shade.keyboard.translate

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
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
        /** Every change to the input field (typed, pasted or cleared) keeps the service in sync. */
        fun onInputChanged(text: String)
    }

    private var theme: KbTheme = Themes.LIGHT

    private val titleView = TextView(context)
    private val targetChip = TextView(context)
    private val closeBtn = TextView(context)
    private val inputField = EditText(context)
    private val resultView = TextView(context)
    private val insertBtn = TextView(context)

    // Display state (declared BEFORE the init block below — init reads them through
    // setTheme → refreshResult, and Kotlin runs initializers in declaration order).
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

        // --- input field -------------------------------------------------------------------------
        // A real, editable field: blinking cursor, cursor placement by tap and the standard
        // long-press → Paste menu. showSoftInputOnFocus=false keeps the system IME shut — the
        // Shade keyboard below feeds this field (see ShadeService.onTranslatorKey).
        inputField.hint = context.getString(R.string.translator_hint)
        inputField.isSingleLine = true
        inputField.maxLines = 1
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputField.gravity = Gravity.CENTER_VERTICAL
        inputField.setPadding(dp(12f), 0, dp(12f), 0)
        inputField.showSoftInputOnFocus = false
        // Every text change — forwarded keystrokes, a paste, or a reset — is reported so
        // the service's translator state never drifts from what is on screen.
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                callbacks.onInputChanged(s?.toString().orEmpty())
            }
        })
        addView(inputField, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)).apply {
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
        inputField.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        inputField.setTextColor(t.text)
        inputField.setHintTextColor(t.hint)
        resultView.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        insertBtn.setTextColor(t.accentText)
        insertBtn.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(t.accent)
        }
        refreshResult()
    }

    /** Replaces the whole field (used when the panel opens or is reset). */
    fun setQuery(q: String) {
        if (inputField.text.toString() == q) return
        inputField.setText(q)
        inputField.setSelection(inputField.text.length)
    }

    /** Current field contents — authoritative for the service's translate call. */
    fun inputText(): String = inputField.text.toString()

    /** Inserts forwarded keyboard text at the cursor (replacing any selection). */
    fun appendInput(text: String) {
        val sb = inputField.editableText
        var start = inputField.selectionStart
        var end = inputField.selectionEnd
        if (start < 0 || end < 0 || start > sb.length || end > sb.length) {
            start = sb.length; end = start
        }
        val from = minOf(start, end)
        sb.replace(from, maxOf(start, end), text)
        inputField.setSelection(minOf(from + text.length, sb.length))
    }

    /** Deletes the current selection, or the code point before the cursor. */
    fun backspaceInput() {
        val sb = inputField.editableText
        var start = inputField.selectionStart
        var end = inputField.selectionEnd
        if (start < 0 || end < 0 || start > sb.length || end > sb.length) {
            start = sb.length; end = start
        }
        if (start != end) {
            val from = minOf(start, end)
            sb.delete(from, maxOf(start, end))
            inputField.setSelection(from)
            return
        }
        if (start > 0) {
            val delStart = sb.toString().offsetByCodePoints(start, -1)
            sb.delete(delStart, start)
        }
    }

    /** Puts the cursor in the field: blinking caret + long-press paste ready immediately. */
    fun focusInput() {
        inputField.requestFocus()
        inputField.setSelection(inputField.text.length)
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

    private fun refreshResult() {
        if (hasResult) {
            resultView.text = resultText
        } else {
            resultView.text = statusText
        }
    }
}
