package com.shade.keyboard.gif

import androidx.core.widget.TextViewCompat
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shade.keyboard.KbTheme
import com.shade.keyboard.R
import com.shade.keyboard.Themes

/**
 * GIF picker laid out like the emoji panel: two pieces.
 *
 *  - [header] — ShadeService pins this above the keyboard content, directly in
 *    the place of the suggestion strip. It holds a back button, the
 *    emoji-style search field (rounded pill, magnifier icon, ✕ clear) and the
 *    first-run setup card for pasting a GIF API key.
 *  - the panel body itself — a vertically scrolling two-column grid of animated
 *    thumbnails (trending results first; searches replace it), plus a centered
 *    overlay for status messages and the one-time consent notice.
 *
 * Typing model (mirrors the emoji search box): tapping the field hides the grid
 * and raises the real keyboard below the header — [onInputModeChanged] tells the
 * service — and the service routes every keystroke into the query via
 * [typeIntoSearch]/[searchBackspace], with Enter submitting ([submitSearch]).
 * While no API key is configured the setup card captures the forwarded
 * keystrokes instead ([appendKeyInput]/[backspaceKeyInput]/[submitKeyInput]).
 */
// Built programmatically with its dependencies; never inflated from XML.
@SuppressLint("ViewConstructor")
class GifPanel(
    context: Context,
    private val loader: GifImageLoader,
    private val callbacks: Callbacks
) : FrameLayout(context) {

    interface Callbacks {
        fun onClose()
        fun onPick(item: GifItem)
        fun onNearEnd()
        fun onConsent()

        /** Called with the API key the user typed in the setup card (already trimmed). */
        fun onApiKeySaved(key: String)

        /** The query text changed while the user types (the controller runs the debounced live search). */
        fun onQueryEdited(query: String)

        /** Enter confirmed the query (the controller loads it immediately). */
        fun onSearchSubmitted(query: String)

        /** The ✕ button cleared the query (the controller falls back to trending). */
        fun onQueryCleared()
    }

    private var theme: KbTheme = Themes.LIGHT

    // ── Header (lives in the service's root layout, above the keyboard content) ──

    /** Back button + search field row + setup card; replaces the suggestion strip in GIF mode. */
    val header = LinearLayout(context)

    private val closeBtn = TextView(context)
    private val searchField = EditText(context)
    private val searchClear = TextView(context)
    private val attribution = TextView(context)

    // Setup card (shown while no API key is configured): message + key field + save button.
    private val setupCard = LinearLayout(context)
    private val keyMessage = TextView(context)
    private val keyField = EditText(context)
    private val keySaveBtn = TextView(context)
    private var capturingKey = false

    // ── Body: vertical grid + status/consent overlay ────────────────────────

    private val grid = RecyclerView(context)
    private val overlay = LinearLayout(context)
    private val status = TextView(context)
    private val consentBtn = TextView(context)

    private var searchMode = false

    /**
     * Fired when the search field is activated or dismissed, or when the setup card
     * starts/stops capturing the keyboard. ShadeService uses it to swap the GIF grid
     * for the real keyboard underneath the header (same model as the emoji search).
     */
    var onInputModeChanged: ((Boolean) -> Unit)? = null

    /** True while the user is typing a GIF search (keyboard visible below the header). */
    val isSearching: Boolean get() = searchMode

    /** True while the forwarded keystrokes feed the API key field. */
    val isCapturingKey: Boolean get() = capturingKey

    /** True while the panel owns the keyboard (query typing or API-key capture). */
    val ownsKeyboard: Boolean get() = searchMode || capturingKey

    private val items = ArrayList<GifItem>()

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ── Grid adapter ──────────────────────────────────────────────────────

    private inner class GifAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class VH(val thumb: ImageView) : RecyclerView.ViewHolder(thumb)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10f).toFloat())
                    }
                }
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp(THUMB_HEIGHT_DP.toFloat())
                ).apply {
                    setMargins(dp(3f), dp(6f), dp(3f), dp(6f))
                }
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val vh = holder as VH
            val item = items[position]
            vh.thumb.background = thumbBackground()
            vh.thumb.contentDescription = item.title.ifBlank { context.getString(R.string.gif_item) }
            vh.thumb.setOnClickListener { callbacks.onPick(item) }
            loader.load(item.preview.url, vh.thumb)
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            loader.release((holder as VH).thumb)
        }
    }

    private val adapter = GifAdapter()

    private fun thumbBackground() = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(theme.keyFn)
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        buildHeader()
        buildGrid()
    }

    /** Header = back button + search field row, plus the setup card (hidden until needed). */
    private fun buildHeader() {
        header.orientation = LinearLayout.VERTICAL

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(8f), dp(4f))
            // Tapping the row around the field must behave exactly like tapping the field
            // itself — one tap, keyboard up (no-op while the setup card captures input).
            setOnClickListener { enterSearch() }
        }

        closeBtn.text = "←"
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        closeBtn.contentDescription = context.getString(R.string.gif_close)
        closeBtn.setOnClickListener { callbacks.onClose() }
        row.addView(closeBtn, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        searchField.hint = context.getString(R.string.gif_search_hint)
        searchField.maxLines = 1
        searchField.isSingleLine = true
        searchField.textSize = 14f
        searchField.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        searchField.compoundDrawablePadding = dp(8f)
        searchField.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        // The real keyboard is drawn below the header; this field must never ask
        // the system for another IME.
        searchField.showSoftInputOnFocus = false
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (q.length > MAX_QUERY) {
                    searchField.setText(q.take(MAX_QUERY))
                    searchField.setSelection(searchField.text.length)
                    return
                }
                syncRowChrome()
                if (searchMode) callbacks.onQueryEdited(q)
            }
        })
        searchField.setOnClickListener { enterSearch() }
        // Some taps only grant focus (e.g. the first tap after the panel opens, or when the
        // click is consumed by cursor placement). Entering search on focus as well guarantees
        // the keyboard appears on the FIRST tap, never the second.
        searchField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) enterSearch()
        }
        // Hardware enter submits the query and returns to the grid.
        searchField.setOnEditorActionListener { _, _, _ -> submitSearch(); true }
        row.addView(searchField, LinearLayout.LayoutParams(0, dp(40f), 1f))

        searchClear.text = "✕"
        searchClear.textSize = 16f
        searchClear.gravity = Gravity.CENTER
        searchClear.setPadding(dp(10f), 0, dp(10f), 0)
        searchClear.visibility = View.GONE
        searchClear.setOnClickListener { clearQuery() }
        row.addView(searchClear, LinearLayout.LayoutParams(dp(40f), LinearLayout.LayoutParams.MATCH_PARENT))

        attribution.text = context.getString(R.string.gif_powered_by)
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        attribution.typeface = Typeface.DEFAULT_BOLD
        attribution.setPadding(dp(8f), 0, 0, 0)
        row.addView(attribution, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)))
        header.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Setup card: explains that a free API key is needed and captures the key the user
        // types on the keyboard below. Keys typed on the Shade keyboard are forwarded into
        // the field at the cursor (see appendKeyInput / backspaceKeyInput);
        // showSoftInputOnFocus=false keeps the system IME from ever opening on top of this
        // keyboard.
        setupCard.orientation = LinearLayout.VERTICAL
        setupCard.gravity = Gravity.CENTER
        setupCard.visibility = View.GONE
        keyMessage.text = context.getString(R.string.gif_needs_key_message)
        keyMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        keyMessage.gravity = Gravity.CENTER
        keyMessage.setPadding(dp(16f), dp(4f), dp(16f), 0)
        setupCard.addView(keyMessage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        keyField.hint = context.getString(R.string.gif_key_hint)
        keyField.isSingleLine = true
        keyField.maxLines = 1
        keyField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        keyField.gravity = Gravity.CENTER_VERTICAL
        keyField.setPadding(dp(12f), 0, dp(12f), 0)
        keyField.showSoftInputOnFocus = false
        setupCard.addView(keyField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f)).apply {
            marginStart = dp(24f); marginEnd = dp(24f); topMargin = dp(8f)
        })

        keySaveBtn.text = context.getString(R.string.gif_key_save)
        keySaveBtn.gravity = Gravity.CENTER
        keySaveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        keySaveBtn.typeface = Typeface.DEFAULT_BOLD
        keySaveBtn.setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        keySaveBtn.setOnClickListener { saveKey() }
        setupCard.addView(keySaveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        header.addView(setupCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun buildGrid() {
        grid.layoutManager = GridLayoutManager(context, GIF_COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.overScrollMode = OVER_SCROLL_NEVER
        // Infinite scroll: near the bottom of the results, ask the controller for the next page.
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (items.isEmpty()) return
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= items.size - 6) callbacks.onNearEnd()
            }
        })
        addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.CENTER
        overlay.visibility = View.GONE
        status.gravity = Gravity.CENTER
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.setPadding(dp(16f), 0, dp(16f), 0)
        overlay.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        consentBtn.text = context.getString(R.string.gif_consent_continue)
        consentBtn.gravity = Gravity.CENTER
        consentBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        consentBtn.typeface = Typeface.DEFAULT_BOLD
        consentBtn.setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        consentBtn.visibility = View.GONE
        consentBtn.setOnClickListener { callbacks.onConsent() }
        overlay.addView(consentBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    // ── Search mode ───────────────────────────────────────────────────────

    private fun enterSearch() {
        if (capturingKey || searchMode) return
        searchMode = true
        searchField.requestFocus()
        syncRowChrome()
        onInputModeChanged?.invoke(true)
    }

    /** Leaves search mode without touching the query text (used by submit). */
    private fun exitSearchInternal() {
        if (!searchMode) return
        searchMode = false
        searchField.clearFocus()
        syncRowChrome()
        onInputModeChanged?.invoke(false)
    }

    /** ✕ — clears the query; if a search was active the grid comes back and trending reloads. */
    fun clearQuery() {
        searchField.setText("")
        if (searchMode) exitSearchInternal()
        callbacks.onQueryCleared()
    }

    /** Enter — confirms the query and returns to the grid (the text stays for reference). */
    fun submitSearch() {
        if (!searchMode) return
        val q = searchField.text.toString()
        exitSearchInternal()
        callbacks.onSearchSubmitted(q)
    }

    /**
     * Clears search state and every surface without firing callbacks — used when the
     * panel is opened or closed, where the service sets visibilities itself.
     */
    fun reset() {
        searchMode = false
        capturingKey = false
        searchField.isEnabled = true
        searchField.setText("")
        searchField.clearFocus()
        setupCard.visibility = View.GONE
        consentBtn.visibility = View.GONE
        overlay.visibility = View.GONE
        syncRowChrome()
        clearGrid()
    }

    /** Routes keyboard input into the query (called by ShadeService while searching). */
    fun typeIntoSearch(text: String) {
        searchField.append(text)
        searchField.setSelection(searchField.text.length)
    }

    /** Removes the last query code point (backspace key routing while searching). */
    fun searchBackspace() {
        val t = searchField.text
        if (t.isEmpty()) return
        val start = t.toString().offsetByCodePoints(t.length, -1)
        searchField.setText(t.substring(0, start))
        searchField.setSelection(searchField.text.length)
    }

    /** ✕ visibility rules: while searching, or whenever a query text is present. */
    private fun syncRowChrome() {
        val hasText = searchField.text.isNotEmpty()
        searchClear.visibility = if (searchMode || hasText) View.VISIBLE else View.GONE
        attribution.visibility = if (!searchMode && !hasText) View.VISIBLE else View.GONE
    }

    // ── Setup card (no API key configured) ──────────────────────────────────

    /**
     * Setup card: explains that a free API key is needed and captures the key the user
     * types on the keyboard below. The card lives in the header (below the search row),
     * the grid steps aside for the keyboard until the key is saved.
     */
    fun showSetup() {
        if (searchMode) exitSearchInternal()
        capturingKey = true
        searchField.isEnabled = false
        keyField.setText("")
        keyMessage.text = context.getString(R.string.gif_needs_key_message)
        setupCard.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        onInputModeChanged?.invoke(true)
        // Only after everything is visible: requestFocus on a view inside GONE parents is a
        // no-op. Cursor lands in the field right away, so long-press → Paste works without
        // a tap first.
        keyField.requestFocus()
    }

    /** Inserts forwarded keyboard text at the cursor (replacing any selection). */
    fun appendKeyInput(text: String) {
        if (!capturingKey) return
        restoreSetupMessage()
        val sb = keyField.editableText
        var start = keyField.selectionStart
        var end = keyField.selectionEnd
        if (start < 0 || end < 0 || start > sb.length || end > sb.length) {
            start = sb.length; end = start
        }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        sb.replace(from, to, text)
        if (sb.length > MAX_KEY) sb.delete(MAX_KEY, sb.length)
        keyField.setSelection(minOf(from + text.length, sb.length))
    }

    /** Deletes the current selection, or the code point before the cursor. */
    fun backspaceKeyInput() {
        if (!capturingKey) return
        restoreSetupMessage()
        val sb = keyField.editableText
        var start = keyField.selectionStart
        var end = keyField.selectionEnd
        if (start < 0 || end < 0 || start > sb.length || end > sb.length) {
            start = sb.length; end = start
        }
        if (start != end) {
            val from = minOf(start, end)
            sb.delete(from, maxOf(start, end))
            keyField.setSelection(from)
            return
        }
        if (start > 0) {
            val delStart = sb.toString().offsetByCodePoints(start, -1)
            sb.delete(delStart, start)
        }
    }

    /** Enter on the keyboard (or the Save button) persists the key via the controller. */
    fun submitKeyInput() {
        if (!capturingKey) return
        saveKey()
    }

    private fun saveKey() {
        val key = keyField.text.toString().trim()
        if (key.isEmpty()) {
            // Inline feedback in the card — the grid is hidden while the keyboard
            // captures the key, so the overlay in the grid area can't carry the message.
            keyMessage.text = context.getString(R.string.gif_key_hint)
            return
        }
        leaveSetup()
        callbacks.onApiKeySaved(key)
    }

    /** Restores the full setup instructions after the "empty key" inline hint. */
    private fun restoreSetupMessage() {
        if (capturingKey && keyMessage.text.toString() != context.getString(R.string.gif_needs_key_message)) {
            keyMessage.text = context.getString(R.string.gif_needs_key_message)
        }
    }

    private fun leaveSetup() {
        if (!capturingKey) return
        capturingKey = false
        keyField.clearFocus()
        setupCard.visibility = View.GONE
        searchField.isEnabled = true
        onInputModeChanged?.invoke(false)
    }

    // ── Grid content ──────────────────────────────────────────────────────

    /** Replaces the grid contents, or appends the next page to them. */
    fun showItems(newItems: List<GifItem>, append: Boolean) {
        if (capturingKey) leaveSetup()
        if (append) {
            val from = items.size
            items.addAll(newItems)
            adapter.notifyItemRangeInserted(from, newItems.size)
        } else {
            items.clear()
            items.addAll(newItems)
            adapter.notifyDataSetChanged()
            grid.scrollToPosition(0)
        }
        overlay.visibility = View.GONE
    }

    /** Empties the grid (used when the panel closes or the consent notice takes over). */
    fun clearGrid() {
        items.clear()
        adapter.notifyDataSetChanged()
        overlay.visibility = View.GONE
    }

    /** Shows a message in place of the grid (null hides it). */
    fun showStatus(message: String?) {
        if (capturingKey) leaveSetup()
        consentBtn.visibility = View.GONE
        if (message == null) {
            overlay.visibility = View.GONE
            return
        }
        status.text = message
        overlay.visibility = View.VISIBLE
    }

    /** First-use notice: nothing is sent to the GIF provider until the person taps Continue. */
    fun showConsent() {
        if (capturingKey) leaveSetup()
        clearGrid()
        status.text = context.getString(R.string.gif_consent_message)
        consentBtn.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        header.setBackgroundColor(t.bg)
        closeBtn.setTextColor(t.text)
        searchField.setTextColor(t.text)
        searchField.setHintTextColor(t.hint)
        searchField.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(t.keyFn)
        }
        TextViewCompat.setCompoundDrawableTintList(searchField, ColorStateList.valueOf(t.hint))
        searchClear.setTextColor(t.hint)
        attribution.setTextColor(t.hint)
        status.setTextColor(t.hint)
        consentBtn.setTextColor(t.accentText)
        consentBtn.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.accent)
        }
        keyMessage.setTextColor(t.hint)
        keyField.setTextColor(t.text)
        keyField.setHintTextColor(t.hint)
        keyField.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(t.keyFn)
        }
        keySaveBtn.setTextColor(t.accentText)
        keySaveBtn.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(t.accent)
        }
        adapter.notifyDataSetChanged()
    }

    companion object {
        private const val THUMB_HEIGHT_DP = 100
        private const val GIF_COLUMNS = 2
        private const val MAX_QUERY = 60
        private const val MAX_KEY = 80
    }
}
