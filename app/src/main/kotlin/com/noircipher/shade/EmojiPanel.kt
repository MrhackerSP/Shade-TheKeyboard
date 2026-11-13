package com.noircipher.shade

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private val allCategories: List<Pair<String, List<String>>> = listOf(
        "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤔 🤨 😐 😑 😶 🙄 😏 😣 😥 😮 😴 😌 😢 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😰 😓 🤗 🤭 🫡 😎 🤓 🥳 🥺 😬 🙃".split(" "),
        "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 👋 🙏 👏 🙌 💪 👀 🧠 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 💕 💯 🔥 ✨ ⭐ 🎉 🎂".split(" "),
        "🐻" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐺 🐴 🦄 🐝 🦋 🐢 🐍 🐙 🐬 🐳 🌸 🌹 🌻 🌲 🌴 🍀 🌍 🌙 ☀️ ⛅ ☔ ⚡ ❄️".split(" "),
        "🍎" to "🍎 🍌 🍇 🍓 🍒 🍑 🍍 🥑 🍅 🥕 🌽 🍞 🧀 🍗 🍔 🍟 🍕 🌭 🌮 🍣 🍜 🍩 🍪 🍫 🍿 ☕ 🍵 🍺 🍷 🥤".split(" "),
        "⚽" to "⚽ 🏀 🏈 🎾 🎮 🎧 🎸 🎬 📱 💻 📷 💡 📚 ✏️ 🔑 💰 🎁 ⏰ 🚗 🚕 🚌 ✈️ 🚀 🚲 🏠 🏖️ ⛰️".split(" "),
        "✅" to "✅ ❌ ❗ ❓ ⚠️ 💤 ➕ ➖ ➡️ ⬅️ ⬆️ ⬇️ 🔴 🟢 🔵 ⚪ ⚫ ♻️ ✔️ ©️ ®️ ™️".split(" ")
    )

    // One label per section: index 0 is the recents header, 1..N label the categories in
    // `allCategories` order. buildUnifiedList() reads categoryNames[i + 1], so this list must
    // always have exactly one more entry than allCategories — a shorter list made the last
    // category read past the end (ArrayIndexOutOfBoundsException) and crashed the keyboard
    // the moment its input view was created.
    private val categoryNames = listOf(
        "Recent", "Smileys & People", "Gestures & Hearts", "Animals & Nature",
        "Food & Drink", "Activities & Objects", "Symbols"
    )

    private val skinToneBases: Set<String> = setOf(
        "👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🙏", "✍️", "💅", "🤝"
    )

    private val skinTones = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private val recentIcon = "🕘"

    private val recentPrefs =
        context.getSharedPreferences("shade_emoji_recent", Context.MODE_PRIVATE)
    private var recent: MutableList<String> = loadRecent()

    private var theme: KbTheme = Themes.LIGHT

    /** The single toggleable top bar: shows search box OR suggestion chips. */
    private val topBar = LinearLayout(context)
    private val suggestionScroll = HorizontalScrollView(context)
    private val suggestionRow = LinearLayout(context)
    private val searchBox = EditText(context)

    /** Unified RecyclerView: all categories with section headers, smooth scroll. */
    private val emojiList = RecyclerView(context)

    /** Category tab bar at the bottom. */
    private val bar = LinearLayout(context)
    private val abc = TextView(context)
    private val del = ImageView(context)
    private val tabs = ArrayList<TextView>()
    private var selected = 0
    private var searchMode = false

    /** keyword -> list of emojis, loaded lazily from assets/emoji/emoji_keywords_*.tsv */
    private val keywordMap: Map<String, List<String>> by lazy { loadKeywordMap() }

    /** Callbacks for search mode. */
    var onSearchFocus: (() -> Unit)? = null
    var onSearchExit: (() -> Unit)? = null

    val searchQuery: String get() = searchBox.text.toString()

    fun setSearchQuery(query: String) {
        searchBox.setText(query)
        searchBox.setSelection(query.length)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ── Unified list item types ───────────────────────────────────────────

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_EMOJI = 1
        const val MAX_RECENT = 32
        const val EMOJI_COLUMNS = 8
        const val SPAN_HEADER = 8
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
        for (i in allCategories.indices) {
            // getOrNull keeps a mismatched names list from crashing the keyboard; the
            // categoryNames comment above documents the required invariant.
            out += ListItem.HeaderItem(categoryNames.getOrNull(i + 1) ?: "Category")
            allCategories[i].second.forEach { out += ListItem.EmojiItem(it) }
        }
        return out
    }

    private var unifiedItems: List<ListItem> = emptyList()

    // ── Search results grid ────────────────────────────────────────────────

    private val searchGrid = GridView(context)
    private var searchResults: List<String> = emptyList()
    private val searchAdapter = object : BaseAdapter() {
        override fun getCount() = searchResults.size
        override fun getItem(position: Int) = searchResults[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(0, dp(8f), 0, dp(8f))
            }
            tv.text = searchResults[position]
            return tv
        }
    }

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

        // ── Top bar: search box OR suggestion chips (toggled) ─────────────
        topBar.orientation = VERTICAL

        // Search box — hidden by default, shown when user taps the placeholder
        searchBox.hint = context.getString(R.string.emoji_search_hint)
        searchBox.maxLines = 1
        searchBox.textSize = 14f
        searchBox.isSingleLine = true
        searchBox.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        searchBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(theme.keyFn)
        }
        searchBox.visibility = GONE
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty().trim())
            }
        })
        searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !searchMode) enterSearch()
        }
        topBar.addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        // Suggestion scroll (horizontal chips) — shown by default when suggestions exist
        suggestionScroll.isHorizontalScrollBarEnabled = false
        suggestionScroll.visibility = GONE
        suggestionRow.orientation = HORIZONTAL
        suggestionScroll.addView(
            suggestionRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        topBar.addView(suggestionScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        // Search placeholder — tappable bar that shows "Search emoji" hint.
        // When no suggestions are present, this is what the user sees.
        // Tapping it shows the search box and focuses it.
        val searchPlaceholder = TextView(context).apply {
            text = context.getString(R.string.emoji_search_hint)
            textSize = 14f
            setTextColor(theme.hint)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(theme.keyFn)
            }
            setOnClickListener {
                searchBox.visibility = VISIBLE
                searchBox.requestFocus()
            }
        }
        topBar.addView(searchPlaceholder, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Search results grid (hidden by default) ────────────────────────
        searchGrid.numColumns = EMOJI_COLUMNS
        searchGrid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        searchGrid.adapter = searchAdapter
        searchGrid.setSelector(android.R.color.transparent)
        searchGrid.visibility = GONE
        searchGrid.setOnItemClickListener { _, _, pos, _ ->
            val emoji = searchResults[pos]
            recordRecent(emoji)
            onEmoji(emoji)
        }
        addView(searchGrid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Unified emoji list (all categories with headers, smooth scroll) ──
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

        // ── Category bar (bottom) ────────────────────────────────────────
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
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { scrollToCategory(0) }
        }
        tabs.add(recentTab)
        bar.addView(recentTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Category tabs (index 1+)
        allCategories.forEachIndexed { i, (icon, _) ->
            val t = TextView(context).apply {
                text = icon
                textSize = 20f
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

        // Build initial unified list
        rebuildUnifiedList()
    }

    private fun rebuildUnifiedList() {
        unifiedItems = buildUnifiedList()
        unifiedAdapter.notifyDataSetChanged()
    }

    /** Scroll the unified list to a category by index (0=Recent, 1+=categories). */
    private fun scrollToCategory(catIndex: Int) {
        if (searchMode) exitSearch()
        selected = catIndex
        // Find the header position for this category
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
        // Hide suggestion scroll and placeholder
        suggestionScroll.visibility = GONE
        if (topBar.childCount >= 3) topBar.getChildAt(2).visibility = GONE
        // Search box is already VISIBLE (it triggered the focus change)
        // Show search results grid, hide the unified list
        searchGrid.visibility = VISIBLE
        emojiList.visibility = GONE
        // Hide the category bar while searching
        bar.visibility = GONE
        onSearchFocus?.invoke()
        paintTabs()
    }

    fun exitSearch() {
        if (!searchMode) return
        searchMode = false
        searchBox.setText("")
        searchBox.clearFocus()
        searchBox.visibility = GONE
        searchGrid.visibility = GONE
        searchResults = emptyList()
        searchAdapter.notifyDataSetChanged()
        // Restore the unified list + category bar
        emojiList.visibility = VISIBLE
        bar.visibility = VISIBLE
        // Restore the search placeholder
        if (topBar.childCount >= 3) topBar.getChildAt(2).visibility = VISIBLE
        // Restore suggestions if any
        if (suggestionRow.childCount > 0) suggestionScroll.visibility = VISIBLE
        onSearchExit?.invoke()
    }

    private fun applySearch(query: String) {
        if (query.isEmpty()) {
            searchResults = emptyList()
            searchAdapter.notifyDataSetChanged()
            return
        }

        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            searchResults = emptyList()
            searchAdapter.notifyDataSetChanged()
            return
        }

        data class Scored(val emoji: String, val score: Float)
        val scored = ArrayList<Scored>()

        for ((keyword, emojis) in keywordMap) {
            val kwLower = keyword.lowercase()
            if (!tokens.all { kwLower.contains(it) }) continue
            var score = keyword.length.toFloat()
            for (tok in tokens) {
                if (kwLower == tok) score -= 10f
                else if (kwLower.startsWith(tok)) score -= 5f
            }
            for (e in emojis) scored.add(Scored(e, score))
        }

        val results = LinkedHashSet<String>()
        for ((emoji, _) in scored.sortedBy { it.score }) {
            results.add(emoji)
            if (results.size >= 96) break
        }

        searchResults = results.toList()
        searchAdapter.notifyDataSetChanged()
        searchGrid.setSelection(0)
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

    fun recordRecent(emoji: String) {
        if (emoji.isBlank()) return
        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        recentPrefs.edit()
            .putStringSet("items", recent.toSet())
            .putString("order", recent.joinToString("\n"))
            .apply()
        if (!searchMode) rebuildUnifiedList()
    }

    // ── Keyword map loading ────────────────────────────────────────────────

    private fun loadKeywordMap(): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (tag in listOf("en", "hi", "concepts")) {
            try {
                context.assets.open("emoji/emoji_keywords_${tag}.tsv").bufferedReader().useLines { lines ->
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
            } catch (_: Exception) {}
        }
        return map
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setSuggestions(suggestions: List<String>) {
        suggestionRow.removeAllViews()
        if (searchMode || suggestions.isEmpty()) {
            suggestionScroll.visibility = GONE
            if (topBar.childCount >= 3) topBar.getChildAt(2).visibility = VISIBLE
            return
        }
        // Show suggestion row, hide the search placeholder
        suggestionScroll.visibility = VISIBLE
        if (topBar.childCount >= 3) topBar.getChildAt(2).visibility = GONE
        suggestions.take(10).forEach { emoji ->
            val tv = TextView(context).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(12f), 0, dp(12f), 0)
                setOnClickListener {
                    recordRecent(emoji)
                    onEmoji(emoji)
                }
            }
            suggestionRow.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        abc.setTextColor(t.text)
        del.setColorFilter(t.text)
        searchBox.setTextColor(t.text)
        searchBox.setHintTextColor(t.hint)
        searchBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(t.keyFn)
        }
        for (i in 0 until suggestionRow.childCount) {
            (suggestionRow.getChildAt(i) as? TextView)?.setTextColor(t.text)
        }
        paintTabs()
        unifiedAdapter.notifyDataSetChanged()
    }

    fun onShow() {
        if (searchMode) exitSearch()
        rebuildUnifiedList()
        emojiList.scrollToPosition(0)
    }

    fun onBackPressed(): Boolean {
        if (searchMode) {
            exitSearch()
            return true
        }
        return false
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            t.background = if (i == selected && !searchMode) GradientDrawable().apply {
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


/** Keyword -> emoji for inline emoji suggestions while typing. */
object EmojiKeywords {
    val map: Map<String, String> = mapOf(
        "love" to "❤️", "heart" to "❤️", "happy" to "😊", "smile" to "😀", "laugh" to "😂",
        "lol" to "😂", "sad" to "😢", "cry" to "😭", "angry" to "😠", "cool" to "😎",
        "thanks" to "🙏", "thank" to "🙏", "please" to "🙏", "pray" to "🙏", "yes" to "👍",
        "ok" to "👌", "okay" to "👌", "good" to "👍", "no" to "👎", "bad" to "👎", "party" to "🎉",
        "birthday" to "🎂", "congrats" to "🎉", "congratulations" to "🎉", "fire" to "🔥",
        "hot" to "🔥", "star" to "⭐", "sun" to "☀️", "moon" to "🌙", "rain" to "☔",
        "snow" to "❄️", "cat" to "🐱", "dog" to "🐶", "pizza" to "🍕", "coffee" to "☕",
        "beer" to "🍺", "food" to "🍔", "car" to "🚗", "home" to "🏠", "house" to "🏠",
        "phone" to "📱", "money" to "💰", "gift" to "🎁", "music" to "🎵", "book" to "📚",
        "sleep" to "😴", "tired" to "😴", "think" to "🤔", "wow" to "😮", "sorry" to "🙏",
        "hello" to "👋", "hi" to "👋", "bye" to "👋", "goodbye" to "👋", "strong" to "💪",
        "idea" to "💡", "time" to "⏰", "world" to "🌍", "rocket" to "🚀", "flower" to "🌸"
    )
}
