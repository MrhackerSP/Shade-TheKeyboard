package com.noircipher.shade

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Emoji panel: two pieces.
 *
 *  - [header] — ShadeService pins this above the keyboard content, directly in
 *    the place of the suggestion strip. It holds the emoji search field and,
 *    while a query is active, a results bar that opens up above the field and is
 *    used only for showing emoji results. Tapping the field hides the emoji grid
 *    and draws the real keyboard below the search box; ShadeService routes the
 *    typed keys into the query, and matching emojis stream into the results bar.
 *  - the panel body itself — a unified scrolling grid (the Recent section first,
 *    then every [EmojiCatalog] category with section headers) plus the bottom tab
 *    bar (ABC / category tabs / ⌫).
 *
 * Tapping an emoji — in the results bar, the grid or the skin-tone popup —
 * records it in the Recent section immediately, so the recently used emojis at
 * the top of the list stay up to date in real time.
 */
class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private val categories = EmojiCatalog.categories

    // One label per section: index 0 is the recents header, 1..N label the
    // categories. buildUnifiedList() reads categoryNames[i + 1], so this list
    // must always have exactly one more entry than there are categories.
    private val categoryNames = listOf("Recent") + categories.map { it.name }

    private val skinToneBases: Set<String> = setOf(
        "👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🫱", "🫲", "🫳", "🫴", "🫷", "🫸", "👐", "🤲", "🤝", "🙏", "✍️",
        "💅", "🤳", "💪", "🦾", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🫀", "🫁", "🦷",
        "🦴", "👀", "👁️", "👅", "👄", "🫦", "🫹", "🫺"
    )

    private val skinTones = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private val recentIcon = "🕘"

    private val recentPrefs =
        context.getSharedPreferences("shade_emoji_recent", Context.MODE_PRIVATE)
    private var recent: MutableList<String> = loadRecent()

    private var theme: KbTheme = Themes.LIGHT

    // ── Header (lives in the service's root layout, above the keyboard content) ──

    /** Search field row + results bar; replaces the suggestion strip in emoji mode. */
    val header = LinearLayout(context)

    /** Results strip above the search box; opens only when a query has matches. */
    private val resultsBar = HorizontalScrollView(context)
    private val resultsRow = LinearLayout(context)
    private val searchField = EditText(context)
    private val searchClear = TextView(context)

    private var searchMode = false

    /**
     * Fired when the search field is activated or dismissed. ShadeService uses it
     * to swap the emoji grid for the real keyboard underneath the header.
     */
    var onSearchModeChanged: ((Boolean) -> Unit)? = null

    /** True while the user is typing an emoji search (keyboard visible below the header). */
    val isSearching: Boolean get() = searchMode

    // ── Body ──────────────────────────────────────────────────────────────

    /** Unified RecyclerView: all categories with section headers, smooth scroll. */
    private val emojiList = RecyclerView(context)

    /** Category tab bar at the bottom. */
    private val bar = LinearLayout(context)
    private val abc = TextView(context)
    private val del = ImageView(context)
    private val tabs = ArrayList<TextView>()
    private var selected = 0

    /** keyword -> list of emojis, loaded lazily from assets/emoji/emoji_keywords_*.tsv */
    private val keywordMap: Map<String, List<String>> by lazy { loadKeywordMap() }

    /** emoji -> weight-ordered related emojis, for the thin-results search fill */
    private val relatedMap: Map<String, List<String>> by lazy { loadRelatedMap() }

    /** query word -> canonical keyword alternatives, for the synonym search bridge */
    private val synonymMap: Map<String, List<String>> by lazy { loadSynonymMap() }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ── Unified list item types ───────────────────────────────────────────

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_EMOJI = 1
        const val MAX_RECENT = 32
        const val EMOJI_COLUMNS = 8
        const val SPAN_HEADER = 8

        /** Upper bound for the results bar above the search box. */
        const val SEARCH_RESULTS_LIMIT = 48
    }

    private sealed class ListItem {
        data class HeaderItem(val title: String) : ListItem()
        data class EmojiItem(val emoji: String) : ListItem()
    }

    /** Build the flat list: Recent header + recents, then each category header + its emojis. */
    private fun buildUnifiedList(): List<ListItem> {
        val out = ArrayList<ListItem>()
        if (recent.isNotEmpty()) {
            out += ListItem.HeaderItem(categoryNames[0])
            recent.take(MAX_RECENT).forEach { out += ListItem.EmojiItem(it) }
        }
        for (i in categories.indices) {
            // getOrNull keeps a mismatched names list from crashing the keyboard; the
            // categoryNames comment above documents the required invariant.
            out += ListItem.HeaderItem(categoryNames.getOrNull(i + 1) ?: "Category")
            categories[i].emojis.forEach { out += ListItem.EmojiItem(it) }
        }
        return out
    }

    private var unifiedItems: List<ListItem> = emptyList()

    // ── Unified RecyclerView adapter ──────────────────────────────────────

    private val unifiedAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = when (unifiedItems[position]) {
            is ListItem.HeaderItem -> TYPE_HEADER
            is ListItem.EmojiItem -> TYPE_EMOJI
        }

        override fun getItemCount() = unifiedItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_HEADER -> {
                    val tv = TextView(context).apply {
                        textSize = 13f
                        setTextColor(theme.hint)
                        setPadding(dp(16f), dp(12f), dp(16f), dp(4f))
                    }
                    HeaderVH(tv)
                }
                else -> {
                    val tv = TextView(context).apply {
                        gravity = Gravity.CENTER
                        textSize = 24f
                        setPadding(0, dp(6f), 0, dp(6f))
                    }
                    EmojiVH(tv)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = unifiedItems[position]) {
                is ListItem.HeaderItem -> {
                    // `itemView` comes from the RecyclerView library, so the compiler will not
                    // smart-cast it after an `as` cast; keep the cast result in a local instead.
                    val view = holder.itemView as TextView
                    view.text = item.title
                    view.setTextColor(theme.hint)
                }
                is ListItem.EmojiItem -> {
                    val view = holder.itemView as TextView
                    view.text = item.emoji
                    view.setOnClickListener {
                        recordRecent(item.emoji)
                        onEmoji(item.emoji)
                    }
                    view.setOnLongClickListener {
                        val base = skinToneBases.firstOrNull { item.emoji.startsWith(it) }
                        if (base != null) {
                            showSkinTonePopup(view, base)
                            true
                        } else false
                    }
                }
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
        inner class EmojiVH(v: View) : RecyclerView.ViewHolder(v)
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        orientation = VERTICAL
        buildHeader()
        buildEmojiList()
        buildBottomBar()
        rebuildUnifiedList()
    }

    /** Header = results bar (hidden until a query matches) + the search box row. */
    private fun buildHeader() {
        header.orientation = VERTICAL

        resultsBar.isHorizontalScrollBarEnabled = false
        resultsBar.visibility = GONE
        resultsRow.orientation = HORIZONTAL
        resultsRow.gravity = Gravity.CENTER_VERTICAL
        resultsBar.addView(
            resultsRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        header.addView(resultsBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f)))

        searchField.hint = context.getString(R.string.emoji_search_hint)
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
                applySearch(s?.toString().orEmpty())
            }
        })
        searchField.setOnClickListener { enterSearch() }
        // Hardware enter closes the search and returns to the emoji grid.
        searchField.setOnEditorActionListener { _, _, _ -> exitSearch(); true }

        searchClear.text = "✕"
        searchClear.textSize = 16f
        searchClear.gravity = Gravity.CENTER
        searchClear.setPadding(dp(10f), 0, dp(10f), 0)
        searchClear.visibility = GONE
        searchClear.setOnClickListener { exitSearch() }

        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(8f))
        }
        searchRow.addView(searchField, LayoutParams(0, dp(40f), 1f))
        searchRow.addView(searchClear, LayoutParams(dp(40f), LayoutParams.MATCH_PARENT))
        header.addView(searchRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildEmojiList() {
        val layoutManager = GridLayoutManager(context, EMOJI_COLUMNS)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position >= unifiedItems.size) return 1
                return when (unifiedItems[position]) {
                    is ListItem.HeaderItem -> SPAN_HEADER
                    is ListItem.EmojiItem -> 1
                }
            }
        }
        emojiList.layoutManager = layoutManager
        emojiList.adapter = unifiedAdapter
        emojiList.setHasFixedSize(true)
        addView(emojiList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildBottomBar() {
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))

        // Recent tab (index 0)
        val recentTab = TextView(context).apply {
            text = recentIcon
            textSize = 17f
            gravity = Gravity.CENTER
            setOnClickListener { scrollToCategory(0) }
        }
        tabs.add(recentTab)
        bar.addView(recentTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Category tabs (index 1+)
        categories.forEachIndexed { i, cat ->
            val t = TextView(context).apply {
                text = cat.icon
                textSize = 17f
                gravity = Gravity.CENTER
                setOnClickListener { scrollToCategory(i + 1) }
            }
            tabs.add(t)
            bar.addView(t, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
    }

    private fun rebuildUnifiedList() {
        unifiedItems = buildUnifiedList()
        unifiedAdapter.notifyDataSetChanged()
    }

    /** Scroll the unified list to a section by index (0=Recent, 1+=categories). */
    private fun scrollToCategory(catIndex: Int) {
        selected = catIndex
        // Find the header position for this section
        var headerPos = -1
        var currentCat = 0
        for (i in unifiedItems.indices) {
            if (unifiedItems[i] is ListItem.HeaderItem) {
                if (currentCat == catIndex) {
                    headerPos = i
                    break
                }
                currentCat++
            }
        }
        if (headerPos >= 0) {
            emojiList.smoothScrollToPosition(headerPos)
        }
        paintTabs()
    }

    // ── Search mode ───────────────────────────────────────────────────────

    private fun enterSearch() {
        if (searchMode) return
        searchMode = true
        searchClear.visibility = VISIBLE
        searchField.requestFocus()
        onSearchModeChanged?.invoke(true)
    }

    fun exitSearch() {
        if (!searchMode) return
        searchMode = false
        searchField.setText("")
        searchField.clearFocus()
        searchClear.visibility = GONE
        searchResults = emptyList()
        rebuildResultsRow()
        resultsBar.visibility = GONE
        onSearchModeChanged?.invoke(false)
    }

    /**
     * Clears search state without firing [onSearchModeChanged] — used when the
     * panel is closed or re-opened, where the service sets visibilities itself.
     */
    fun resetSearch() {
        searchMode = false
        searchField.setText("")
        searchField.clearFocus()
        searchClear.visibility = GONE
        searchResults = emptyList()
        rebuildResultsRow()
        resultsBar.visibility = GONE
    }

    /** Routes keyboard input into the query (called by ShadeService while searching). */
    fun typeIntoSearch(text: String) {
        searchField.append(text)
        searchField.setSelection(searchField.text.length)
    }

    /** Removes the last query character (backspace key routing while searching). */
    fun searchBackspace() {
        val t = searchField.text
        if (t.isNotEmpty()) {
            searchField.setText(t.substring(0, t.length - 1))
            searchField.setSelection(searchField.text.length)
        }
    }

    private fun applySearch(query: String) {
        val results = if (searchMode) {
            EmojiSearch.search(query, keywordMap, SEARCH_RESULTS_LIMIT, relatedMap, synonymMap)
        } else {
            emptyList()
        }
        searchResults = results
        rebuildResultsRow()
        // The results bar opens up only for showing emoji results.
        resultsBar.visibility = if (searchMode && results.isNotEmpty()) VISIBLE else GONE
    }

    // ── Results bar ───────────────────────────────────────────────────────

    private var searchResults: List<String> = emptyList()

    private fun rebuildResultsRow() {
        resultsRow.removeAllViews()
        for (emoji in searchResults) {
            val tv = TextView(context).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(dp(12f), 0, dp(12f), 0)
                setOnClickListener {
                    recordRecent(emoji)
                    onEmoji(emoji)
                }
            }
            resultsRow.addView(
                tv,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    // ── Recent ────────────────────────────────────────────────────────────

    private fun loadRecent(): MutableList<String> {
        val set = recentPrefs.getStringSet("items", emptySet()) ?: return mutableListOf()
        val order = recentPrefs.getString("order", "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }
        val ordered = order.filter { set.contains(it) }.toMutableList()
        for (e in set) if (e !in ordered) ordered.add(e)
        while (ordered.size > MAX_RECENT) ordered.removeAt(ordered.size - 1)
        return ordered
    }

    /**
     * Moves [emoji] to the front of the Recent section and persists it. Called on
     * every tap — from the results bar, the grid or the skin-tone popup — so the
     * recently used emojis at the top of the list update in real time.
     */
    fun recordRecent(emoji: String) {
        if (emoji.isBlank()) return
        val anchor = (emojiList.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition() ?: -1
        val oldIndex = unifiedItems.indexOfFirst { it is ListItem.EmojiItem && it.emoji == emoji }
        val wasInRecents = recent.contains(emoji)

        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        recentPrefs.edit()
            .putStringSet("items", recent.toSet())
            .putString("order", recent.joinToString("\n"))
            .apply()

        // Always rebuild — even while the grid is hidden behind the keyboard — so
        // the recents section is current the moment the user leaves search mode.
        rebuildUnifiedList()

        // Keep the viewport stable: moving the tapped emoji to the front of the
        // recents section shifts the rows between it and the top by one. When the
        // recents header is on screen (anchor 0) we stay put so the real-time
        // update is visible.
        if (anchor > 0) {
            val shift = when {
                !wasInRecents -> 1
                oldIndex > anchor -> 1
                else -> 0
            }
            emojiList.scrollToPosition((anchor + shift).coerceAtMost(unifiedItems.size - 1))
        }
    }

    // ── Keyword map loading ────────────────────────────────────────────────

    private fun loadKeywordMap(): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        // Load order = search priority: curated tables first, then the generated
        // CLDR-name catalog, then "shortcuts" last so curated keywords keep their
        // priority and shorthand entries only extend the map.
        for (tag in listOf("en", "hi", "concepts", "catalog", "shortcuts")) {
            com.noircipher.shade.engine.AssetTsv.use(
                context, "emoji/emoji_keywords_${tag}.tsv", "EmojiPanel", Unit
            ) { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val keyword = parts[0].trim().lowercase()
                    val emoji = parts[1].trim()
                    if (keyword.isNotEmpty() && emoji.isNotEmpty()) {
                        map.getOrPut(keyword) { mutableListOf() }.add(emoji)
                    }
                }
            }
        }
        return map
    }

    private fun loadRelatedMap(): Map<String, List<String>> =
        com.noircipher.shade.engine.AssetTsv.use(
            context, "emoji/emoji_related.tsv", "EmojiPanel", emptyMap()
        ) { lines -> EmojiSearch.parseRelatedTsv(lines) }

    private fun loadSynonymMap(): Map<String, List<String>> =
        com.noircipher.shade.engine.AssetTsv.use(
            context, "emoji/emoji_synonyms.tsv", "EmojiPanel", emptyMap()
        ) { lines -> EmojiSearch.parseSynonymTsv(lines) }

    // ── Public API ────────────────────────────────────────────────────────

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        header.setBackgroundColor(t.bg)
        searchField.setTextColor(t.text)
        searchField.setHintTextColor(t.hint)
        searchField.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(t.keyFn)
        }
        searchField.compoundDrawableTintList = ColorStateList.valueOf(t.hint)
        searchClear.setTextColor(t.hint)
        abc.setTextColor(t.text)
        del.setColorFilter(t.text)
        paintTabs()
        unifiedAdapter.notifyDataSetChanged()
    }

    fun onShow() {
        resetSearch()
        rebuildUnifiedList()
        emojiList.scrollToPosition(0)
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            t.background = if (i == selected) GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(theme.keyFn)
            } else null
        }
    }

    // ── Skin tone popup ───────────────────────────────────────────────────

    private fun showSkinTonePopup(anchor: View, base: String) {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(theme.keyFn)
            }
        }
        val variants = listOf(base) + skinTones.map { base + it }
        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.elevation = dp(6f).toFloat()
        popup.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                popup.dismiss()
                true
            } else false
        }
        variants.forEach { variant ->
            val tv = TextView(context).apply {
                text = variant
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                setOnClickListener {
                    recordRecent(variant)
                    onEmoji(variant)
                    popup.dismiss()
                }
            }
            container.addView(tv)
        }
        popup.showAsDropDown(anchor, 0, -anchor.height)
    }
}
