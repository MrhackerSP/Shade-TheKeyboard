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

class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private val baseCategories: List<Pair<String, List<String>>> = listOf(
        "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤔 🤨 😐 😑 😶 🙄 😏 😣 😥 😮 😴 😌 😢 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😰 😓 🤗 🤭 🫡 😎 🤓 🥳 🥺 😬 🙃".split(" "),
        "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 👋 🙏 👏 🙌 💪 👀 🧠 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 💕 💯 🔥 ✨ ⭐ 🎉 🎂".split(" "),
        "🐻" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐺 🐴 🦄 🐝 🦋 🐢 🐍 🐙 🐬 🐳 🌸 🌹 🌻 🌲 🌴 🍀 🌍 🌙 ☀️ ⛅ ☔ ⚡ ❄️".split(" "),
        "🍎" to "🍎 🍌 🍇 🍓 🍒 🍑 🍍 🥑 🍅 🥕 🌽 🍞 🧀 🍗 🍔 🍟 🍕 🌭 🌮 🍣 🍜 🍩 🍪 🍫 🍿 ☕ 🍵 🍺 🍷 🥤".split(" "),
        "⚽" to "⚽ 🏀 🏈 🎾 🎮 🎧 🎸 🎬 📱 💻 📷 💡 📚 ✏️ 🔑 💰 🎁 ⏰ 🚗 🚕 🚌 ✈️ 🚀 🚲 🏠 🏖️ ⛰️".split(" "),
        "✅" to "✅ ❌ ❗ ❓ ⚠️ 💤 ➕ ➖ ➡️ ⬅️ ⬆️ ⬇️ 🔴 🟢 🔵 ⚪ ⚫ ♻️ ✔️ ©️ ®️ ™️".split(" ")
    )

    /**
     * Base emojis that accept a Fitzpatrick skin-tone modifier. When long-pressed
     * a popup with the 5 skin tones is shown.
     */
    private val skinToneBases: Set<String> = setOf(
        "👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🙏", "✍️", "💅", "🤝", "🫱", "🫲", "🫳", "🫴"
    )

    private val skinTones = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private val recentIcon = "🕘"

    private val recentPrefs =
        context.getSharedPreferences("shade_emoji_recent", Context.MODE_PRIVATE)
    private var recent: MutableList<String> = loadRecent()

    private var items: List<String> = baseCategories[0].second
    private val grid = GridView(context)
    private val bar = LinearLayout(context)
    private val abc = TextView(context)
    private val del = ImageView(context)
    private val suggestionScroll = HorizontalScrollView(context)
    private val suggestionRow = LinearLayout(context)
    private val tabs = ArrayList<TextView>()
    private var selected = 0
    private var theme: KbTheme = Themes.LIGHT

    private val searchBox = EditText(context)
    private val emptyView = TextView(context)
    private var searchMode = false

    /** keyword -> list of emojis, loaded lazily from assets/emoji/emoji_keywords_*.tsv */
    private val keywordMap: Map<String, List<String>> by lazy { loadKeywordMap() }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(0, dp(8f), 0, dp(8f))
            }
            tv.text = items[position]
            return tv
        }
    }

    init {
        orientation = VERTICAL

        // Search bar (added above the suggestion row)
        searchBox.hint = context.getString(R.string.emoji_search_hint)
        searchBox.maxLines = 1
        searchBox.textSize = 14f
        searchBox.isSingleLine = true
        searchBox.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        searchBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(theme.keyFn)
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty().trim())
            }
        })
        addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        suggestionScroll.visibility = GONE
        suggestionScroll.isHorizontalScrollBarEnabled = false
        suggestionRow.orientation = HORIZONTAL
        suggestionScroll.addView(
            suggestionRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        addView(suggestionScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        grid.numColumns = 8
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setSelector(android.R.color.transparent)
        grid.setOnItemClickListener { _, _, pos, _ ->
            val emoji = items[pos]
            recordRecent(emoji)
            onEmoji(emoji)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val emoji = items[pos]
            val base = skinToneBases.firstOrNull { emoji.startsWith(it) }
            if (base == null) {
                false
            } else {
                val child = grid.getChildAt(pos - grid.firstVisiblePosition)
                if (child != null) showSkinTonePopup(child, base)
                true
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView.text = context.getString(R.string.emoji_no_results)
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = GONE
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))

        // Recent tab is always present (first), so the user can review their recents
        val recentTab = TextView(context).apply {
            text = recentIcon
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { select(0) }
        }
        tabs.add(recentTab)
        bar.addView(recentTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        baseCategories.forEachIndexed { i, (icon, _) ->
            val t = TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { select(i + 1) }
            }
            tabs.add(t)
            bar.addView(t, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        select(if (recent.isNotEmpty()) 0 else 1)
    }

    private fun select(i: Int) {
        // Selecting a category tab exits search mode (if active) and shows the
        // chosen category. The user can re-enter search by typing in the box.
        // Clear searchMode BEFORE calling setText("") so the TextWatcher's
        // synchronous callback doesn't recurse back into select().
        if (searchMode) {
            searchMode = false
            searchBox.setText("")
        }
        selected = i
        items = when (i) {
            0 -> if (recent.isNotEmpty()) recent else baseCategories[0].second
            else -> baseCategories[i - 1].second
        }
        emptyView.visibility = if (items.isEmpty()) VISIBLE else GONE
        grid.visibility = if (items.isEmpty()) GONE else VISIBLE
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        paintTabs()
    }

    private fun applySearch(query: String) {
        if (query.isEmpty()) {
            if (!searchMode) return
            searchMode = false
            select(selected)
            return
        }
        searchMode = true
        val q = query.lowercase()
        val results = LinkedHashSet<String>()
        for ((keyword, emojis) in keywordMap) {
            if (keyword.contains(q)) {
                results.addAll(emojis)
                if (results.size >= 96) break
            }
        }
        items = results.toList()
        emptyView.visibility = if (items.isEmpty()) VISIBLE else GONE
        grid.visibility = if (items.isEmpty()) GONE else VISIBLE
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        paintTabs()
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            t.background = if (i == selected && !searchMode) GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(theme.keyFn)
            } else null
        }
    }

    private fun showSkinTonePopup(anchor: View, base: String) {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(theme.keyFn)
            }
        }
        // The base (no modifier) first, then the 5 Fitzpatrick variants.
        val variants = listOf(base) + skinTones.map { base + it }
        // IMPORTANT: the popup must NOT be focusable. A focusable PopupWindow
        // steals window focus from the IME, which makes the framework restart
        // the input view (onStartInputView with restarting=true) — that resets
        // the panel to NONE, hiding the emoji grid and showing the letter
        // keyboard behind the popup. A non-focusable popup keeps the IME's
        // focus; child click handlers still fire because the popup window still
        // receives touches on its own content.
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

    private fun loadRecent(): MutableList<String> {
        val set = recentPrefs.getStringSet("items", emptySet()) ?: return mutableListOf()
        // Order isn't preserved by StringSet, so we re-sort by the recorded order map.
        val order = recentPrefs.getString("order", "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }
        val ordered = order.filter { set.contains(it) }.toMutableList()
        // Append any stray entries that aren't in the order list (defensive).
        for (e in set) if (e !in ordered) ordered.add(e)
        while (ordered.size > MAX_RECENT) ordered.removeAt(ordered.size - 1)
        return ordered
    }

    /** Records [emoji] as the most-recently-used. Persisted to SharedPreferences. */
    fun recordRecent(emoji: String) {
        if (emoji.isBlank()) return
        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        recentPrefs.edit()
            .putStringSet("items", recent.toSet())
            .putString("order", recent.joinToString("\n"))
            .apply()
        if (selected == 0 && !searchMode) {
            items = recent
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadKeywordMap(): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (tag in listOf("en", "hi", "hg")) {
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
            } catch (_: Exception) {
                // Asset missing for this language; continue to the next.
            }
        }
        return map
    }

    fun setSuggestions(suggestions: List<String>) {
        suggestionRow.removeAllViews()
        if (suggestions.isEmpty()) {
            suggestionScroll.visibility = GONE
            return
        }

        suggestionScroll.visibility = VISIBLE
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
        emptyView.setTextColor(t.hint)
        for (i in 0 until suggestionRow.childCount) {
            (suggestionRow.getChildAt(i) as? TextView)?.setTextColor(t.text)
        }
        paintTabs()
    }

    companion object {
        private const val MAX_RECENT = 24
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
