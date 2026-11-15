package com.shade.keyboard.gif

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shade.keyboard.KbTheme
import com.shade.keyboard.R
import com.shade.keyboard.Themes

/**
 * Compact GIF picker that sits above the normal keyboard: a query line (typed on the keyboard
 * below, see [GifController]) and a horizontally scrolling strip of animated thumbnails.
 */
// Built programmatically with its dependencies; never inflated from XML.
@SuppressLint("ViewConstructor")
class GifBar(
    context: Context,
    private val loader: GifImageLoader,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onClose()
        fun onPick(item: GifItem)
        fun onNearEnd()
        fun onConsent()

        /** Called with the API key the user typed in the setup card (already trimmed). */
        fun onApiKeySaved(key: String)
    }

    private var theme: KbTheme = Themes.LIGHT
    private var query = ""

    private val closeBtn = TextView(context)
    private val queryView = TextView(context)
    private val attribution = TextView(context)
    private val scroll = HorizontalScrollView(context)
    private val row = LinearLayout(context)
    private val status = TextView(context)
    private val consentBtn = TextView(context)
    private val overlay = LinearLayout(context)

    // Setup card (shown while no API key is configured): message + key display + save button.
    private val setupBox = LinearLayout(context)
    private val keyMessage = TextView(context)
    private val keyView = TextView(context)
    private val keySaveBtn = TextView(context)
    private var keyInput = ""
    private var capturingKey = false
    private lateinit var resultsView: FrameLayout

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        // --- top line: close, query, attribution -------------------------------------------
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(8f), 0)
        }
        closeBtn.text = "✕"
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        closeBtn.contentDescription = context.getString(R.string.gif_close)
        closeBtn.setOnClickListener { callbacks.onClose() }
        top.addView(closeBtn, LayoutParams(dp(40f), dp(36f)))

        queryView.gravity = Gravity.CENTER_VERTICAL
        queryView.maxLines = 1
        queryView.setSingleLine(true)
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        queryView.setPadding(dp(12f), 0, dp(12f), 0)
        top.addView(queryView, LayoutParams(0, dp(36f), 1f))

        attribution.text = context.getString(R.string.gif_powered_by)
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        attribution.typeface = Typeface.DEFAULT_BOLD
        attribution.setPadding(dp(8f), 0, 0, 0)
        top.addView(attribution, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f)))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // --- results strip + status overlay -------------------------------------------------
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        row.orientation = HORIZONTAL
        row.setPadding(dp(4f), 0, dp(4f), 0)
        scroll.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (row.width > 0 && scrollX + scroll.width >= row.width - dp(240f)) callbacks.onNearEnd()
        }

        overlay.orientation = VERTICAL
        overlay.gravity = Gravity.CENTER
        overlay.visibility = GONE
        status.gravity = Gravity.CENTER
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.setPadding(dp(16f), 0, dp(16f), 0)
        overlay.addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        consentBtn.text = context.getString(R.string.gif_consent_continue)
        consentBtn.gravity = Gravity.CENTER
        consentBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        consentBtn.typeface = Typeface.DEFAULT_BOLD
        consentBtn.setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        consentBtn.visibility = GONE
        consentBtn.setOnClickListener { callbacks.onConsent() }
        overlay.addView(consentBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        // --- setup card: key paste via the keyboard below -------------------------------------
        setupBox.orientation = VERTICAL
        setupBox.gravity = Gravity.CENTER
        setupBox.visibility = GONE
        keyMessage.text = context.getString(R.string.gif_needs_key_message)
        keyMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        keyMessage.gravity = Gravity.CENTER
        keyMessage.setPadding(dp(16f), 0, dp(16f), 0)
        setupBox.addView(keyMessage, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // The API key is typed on the keyboard below (keys are forwarded while the bar is open);
        // this display shows what has been typed so far. It never opens the system IME.
        keyView.maxLines = 1
        keyView.ellipsize = null
        keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        keyView.gravity = Gravity.CENTER_VERTICAL
        keyView.setPadding(dp(12f), 0, dp(12f), 0)
        setupBox.addView(keyView, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)).apply {
            marginStart = dp(24f); marginEnd = dp(24f); topMargin = dp(8f)
        })

        keySaveBtn.text = context.getString(R.string.gif_key_save)
        keySaveBtn.gravity = Gravity.CENTER
        keySaveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        keySaveBtn.typeface = Typeface.DEFAULT_BOLD
        keySaveBtn.setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        keySaveBtn.setOnClickListener { saveKey() }
        setupBox.addView(keySaveBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        overlay.addView(setupBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val results = FrameLayout(context).also { resultsView = it }
        results.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        results.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(results, LayoutParams(LayoutParams.MATCH_PARENT, dp(THUMB_HEIGHT_DP + 12f)))

        setTheme(theme)
        setQuery("")
        refreshKeyView()
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        closeBtn.setTextColor(t.text)
        attribution.setTextColor(t.hint)
        status.setTextColor(t.hint)
        consentBtn.setTextColor(t.accentText)
        consentBtn.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.accent)
        }
        queryView.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.keyFn)
        }
        keyMessage.setTextColor(t.hint)
        keyView.setTextColor(t.text)
        keyView.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        keySaveBtn.setTextColor(t.accentText)
        keySaveBtn.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.accent)
        }
        setQuery(query)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as? ImageView)?.background = thumbBackground()
        }
    }

    private fun thumbBackground() = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(theme.keyFn)
    }

    /** Shows what has been typed so far (the keyboard below feeds it, see GifController). */
    fun setQuery(q: String) {
        query = q
        val sb = SpannableStringBuilder()
        if (q.isEmpty()) {
            sb.append(context.getString(R.string.gif_search_hint))
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

    // ---- setup card (no API key configured) ------------------------------------------------

    /** True while the keyboard's forwarded keystrokes feed the API key field. */
    val isCapturingKey: Boolean get() = capturingKey

    /**
     * Setup card: explains that a free API key is needed and captures the key the user types on
     * the keyboard below. The card is taller than the thumbnail strip, so the results container
     * grows to fit it and shrinks back when GIFs load.
     */
    fun showSetup() {
        capturingKey = true
        keyInput = ""
        refreshKeyView()
        status.text = context.getString(R.string.gif_needs_key_title)
        consentBtn.visibility = GONE
        setupBox.visibility = VISIBLE
        // The card is taller than the thumbnail strip: let the results container grow to fit it.
        (resultsView.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = LinearLayout.LayoutParams.WRAP_CONTENT
            resultsView.layoutParams = it
            resultsView.requestLayout()
        }
        overlay.visibility = VISIBLE
    }

    /** Appends typed characters to the API key field (keys forwarded by the service). */
    fun appendKeyInput(text: String) {
        if (!capturingKey) return
        keyInput = (keyInput + text).take(MAX_QUERY)
        refreshKeyView()
    }

    /** Removes the last API-key character (keys forwarded by the service). */
    fun backspaceKeyInput() {
        if (!capturingKey || keyInput.isEmpty()) return
        keyInput = keyInput.substring(0, keyInput.offsetByCodePoints(keyInput.length, -1))
        refreshKeyView()
    }

    /** Enter on the keyboard (or the Save button) persists the key via the controller. */
    fun submitKeyInput() {
        if (!capturingKey) return
        saveKey()
    }

    private fun saveKey() {
        val key = keyInput.trim()
        if (key.isEmpty()) {
            status.text = context.getString(R.string.gif_key_hint)
            return
        }
        leaveSetup()
        callbacks.onApiKeySaved(key)
    }

    private fun refreshKeyView() {
        val sb = SpannableStringBuilder()
        if (keyInput.isEmpty()) {
            sb.append(context.getString(R.string.gif_key_hint))
            sb.setSpan(ForegroundColorSpan(theme.hint), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(keyInput)
            sb.setSpan(ForegroundColorSpan(theme.text), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("|")
            sb.setSpan(ForegroundColorSpan(theme.accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        keyView.text = sb
    }

    private fun leaveSetup() {
        capturingKey = false
        setupBox.visibility = GONE
        (resultsView.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = dp(THUMB_HEIGHT_DP + 12f)
            resultsView.layoutParams = it
            resultsView.requestLayout()
        }
    }

    /** Replaces the thumbnails, or appends the next page to them. */
    fun showItems(items: List<GifItem>, append: Boolean) {
        if (capturingKey) leaveSetup()
        if (!append) clearThumbs()
        for (item in items) addThumb(item)
        if (!append) scroll.scrollTo(0, 0)
        showStatus(null)
    }

    fun clearThumbs() {
        for (i in 0 until row.childCount) (row.getChildAt(i) as? ImageView)?.let { loader.release(it) }
        row.removeAllViews()
    }

    /** Shows a message in place of the thumbnails (null hides it). */
    fun showStatus(message: String?) {
        if (capturingKey) leaveSetup()
        consentBtn.visibility = GONE
        if (message == null) {
            overlay.visibility = GONE
            return
        }
        status.text = message
        overlay.visibility = VISIBLE
    }

    /** First-use notice: nothing is sent to the GIF provider until the person taps Continue. */
    fun showConsent() {
        if (capturingKey) leaveSetup()
        clearThumbs()
        status.text = context.getString(R.string.gif_consent_message)
        consentBtn.visibility = VISIBLE
        overlay.visibility = VISIBLE
    }

    private fun addThumb(item: GifItem) {
        val h = dp(THUMB_HEIGHT_DP.toFloat())
        val ratio = if (item.preview.width > 0 && item.preview.height > 0) {
            item.preview.width.toFloat() / item.preview.height
        } else 1f
        val w = (h * ratio).toInt().coerceIn(dp(64f), dp(200f))
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = thumbBackground()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10f).toFloat())
                }
            }
            contentDescription = item.title.ifBlank { context.getString(R.string.gif_item) }
            setOnClickListener { callbacks.onPick(item) }
        }
        row.addView(iv, LayoutParams(w, h).apply { setMargins(dp(3f), dp(6f), dp(3f), dp(6f)) })
        loader.load(item.preview.url, iv)
    }

    companion object {
        private const val THUMB_HEIGHT_DP = 100
        private const val MAX_QUERY = 80
    }
}
