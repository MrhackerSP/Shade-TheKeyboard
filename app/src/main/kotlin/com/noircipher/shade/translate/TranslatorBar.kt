package com.noircipher.shade.translate

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.noircipher.shade.KbTheme
import com.noircipher.shade.R
import com.noircipher.shade.Themes

/**
 * Visible translator strip that sits above the keyboard (like the GIF bar). While it is open the
 * keyboard's keystrokes are forwarded here instead of the text field: the first row echoes the
 * query being typed, the second row shows the live translation (debounced) or an error, and a
 * language button picks the target language. Enter on the keyboard inserts the translation.
 *
 * This replaces the old invisible flow (toast + hidden buffer), which gave no feedback at all.
 */
class TranslatorBar(
    context: Context,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        /** Enter pressed on the keyboard: commit the current translation. */
        fun onSubmit()
        fun onClose()
        /** Target language picked from the popup (BCP-47-ish code, e.g. "es"). */
        fun onTargetChange(code: String)
    }

    private var theme: KbTheme = Themes.LIGHT

    private val closeBtn = TextView(context)
    private val langBtn = TextView(context)
    private val titleView = TextView(context)
    private val queryView = TextView(context)
    private val resultView = TextView(context)
    private var popup: PopupWindow? = null

    private var query = ""
    private var target = "en"

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        // --- line 1: close, target-language button, title ----------------------------------
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(6f), dp(8f), 0)
        }
        closeBtn.text = "✕"
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        closeBtn.contentDescription = context.getString(R.string.gif_close)
        closeBtn.setOnClickListener { callbacks.onClose() }
        top.addView(closeBtn, LayoutParams(dp(40f), dp(34f)))

        langBtn.gravity = Gravity.CENTER
        langBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        langBtn.typeface = Typeface.DEFAULT_BOLD
        langBtn.setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        langBtn.setOnClickListener { showLanguagePopup() }
        top.addView(langBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34f)).apply {
            leftMargin = dp(4f)
        })

        val title = titleView.apply {
            text = context.getString(R.string.pref_translator)
            textSize = 12f
            setPadding(dp(10f), 0, 0, 0)
        }
        top.addView(title, LayoutParams(0, dp(34f), 1f))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)))

        // --- line 2: the query "text field" (echoed keystrokes) -----------------------------
        queryView.gravity = Gravity.CENTER_VERTICAL
        queryView.maxLines = 1
        queryView.setSingleLine(true)
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        queryView.setPadding(dp(12f), 0, dp(12f), 0)
        addView(queryView, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)).apply {
            leftMargin = dp(4f); rightMargin = dp(4f); topMargin = dp(2f)
        })

        // --- line 3: result / status ---------------------------------------------------------
        resultView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        resultView.maxLines = 2
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        resultView.setPadding(dp(16f), dp(2f), dp(12f), dp(4f))
        addView(resultView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4f)
        })

        setTheme(theme)
        setQuery("")
        setResult(null)
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        closeBtn.setTextColor(t.text)
        titleView.setTextColor(t.hint)
        resultView.setTextColor(t.hint)
        langBtn.setTextColor(t.accentText)
        langBtn.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(t.keyFn)
        }
        queryView.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.keyFn)
        }
        setQuery(query)
    }

    /** Current target language code. */
    fun targetLang(): String = target

    fun setTargetLang(code: String) {
        target = code
        langBtn.text = "🌐 ${displayName(code)} ▾"
    }

    /** Shows what has been typed so far (the keyboard below feeds it). */
    fun setQuery(q: String) {
        query = q
        val sb = SpannableStringBuilder()
        if (q.isEmpty()) {
            sb.append(context.getString(R.string.translator_hint))
            sb.setSpan(ForegroundColorSpan(theme.hint), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(q)
            sb.setSpan(ForegroundColorSpan(theme.text), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("|")
            sb.setSpan(ForegroundColorSpan(theme.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        queryView.text = sb
    }

    /** Live result: null clears to the idle hint, [TranslationService] output fills the row. */
    fun setResult(result: String?) {
        when {
            result == TRANSLATING -> {
                resultView.text = context.getString(R.string.translator_loading)
                resultView.setTextColor(theme.accent)
            }
            result == null -> {
                resultView.text = context.getString(R.string.translator_enter_hint)
                resultView.setTextColor(theme.hint)
            }
            else -> {
                resultView.text = result
                resultView.setTextColor(theme.text)
            }
        }
    }

    fun showError() {
        resultView.text = context.getString(R.string.translator_error)
        resultView.setTextColor(theme.accent)
    }

    /** Target-language popup, shown over the keyboard area (anchored to this bar). */
    private fun showLanguagePopup() {
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(theme.bg)
                setStroke(1, theme.keyFn)
            }
        }
        for ((code, name) in LANGUAGES) {
            val tv = TextView(context).apply {
                text = name
                textSize = 15f
                setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
                setOnClickListener {
                    callbacks.onTargetChange(code)
                    popup?.dismiss()
                }
            }
            if (code == target) tv.setTypeface(null, Typeface.BOLD)
            content.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val win = PopupWindow(content, dp(220f), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        win.isOutsideTouchable = true
        win.setBackgroundDrawable(GradientDrawable())
        popup = win
        win.showAsDropDown(langBtn, 0, dp(4f))
    }

    companion object {
        /** Result-row sentinel while a translation request is in flight. */
        const val TRANSLATING = "\u0000translating"

        /** Curated target languages (code -> display name). */
        val LANGUAGES: List<Pair<String, String>> = listOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "uk" to "Ukrainian",
            "hi" to "Hindi", "ar" to "Arabic", "zh" to "Chinese (Simplified)", "ja" to "Japanese",
            "ko" to "Korean", "tr" to "Turkish", "vi" to "Vietnamese", "th" to "Thai"
        )

        fun displayName(code: String): String =
            LANGUAGES.firstOrNull { it.first == code }?.second ?: code
    }
}
