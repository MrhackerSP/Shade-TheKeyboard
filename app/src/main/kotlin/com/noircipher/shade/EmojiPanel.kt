package com.noircipher.shade

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private val categories: List<Pair<String, List<String>>> = listOf(
        "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 🤔 🤨 😐 😑 😶 🙄 😏 😣 😥 😮 😴 😌 😢 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😰 😓 🤗 🤭 🫡 😎 🤓 🥳 🥺 😬 🙃".split(" "),
        "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 👋 🙏 👏 🙌 💪 👀 🧠 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 💕 💯 🔥 ✨ ⭐ 🎉 🎂".split(" "),
        "🐻" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐺 🐴 🦄 🐝 🦋 🐢 🐍 🐙 🐬 🐳 🌸 🌹 🌻 🌲 🌴 🍀 🌍 🌙 ☀️ ⛅ ☔ ⚡ ❄️".split(" "),
        "🍎" to "🍎 🍌 🍇 🍓 🍒 🍑 🍍 🥑 🍅 🥕 🌽 🍞 🧀 🍗 🍔 🍟 🍕 🌭 🌮 🍣 🍜 🍩 🍪 🍫 🍿 ☕ 🍵 🍺 🍷 🥤".split(" "),
        "⚽" to "⚽ 🏀 🏈 🎾 🎮 🎧 🎸 🎬 📱 💻 📷 💡 📚 ✏️ 🔑 💰 🎁 ⏰ 🚗 🚕 🚌 ✈️ 🚀 🚲 🏠 🏖️ ⛰️".split(" "),
        "✅" to "✅ ❌ ❗ ❓ ⚠️ 💤 ➕ ➖ ➡️ ⬅️ ⬆️ ⬇️ 🔴 🟢 🔵 ⚪ ⚫ ♻️ ✔️ ©️ ®️ ™️".split(" ")
    )

    private var items: List<String> = categories[0].second
    private val grid = GridView(context)
    private val bar = LinearLayout(context)
    private val abc = TextView(context)
    private val del = ImageView(context)
    private val suggestionScroll = HorizontalScrollView(context)
    private val suggestionRow = LinearLayout(context)
    private val tabs = ArrayList<TextView>()
    private var selected = 0
    private var theme: KbTheme = Themes.LIGHT

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
        grid.setOnItemClickListener { _, _, pos, _ -> onEmoji(items[pos]) }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        categories.forEachIndexed { i, (icon, _) ->
            val t = TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { select(i) }
            }
            tabs.add(t)
            bar.addView(t, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
        select(0)
    }

    private fun select(i: Int) {
        selected = i
        items = categories[i].second
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        paintTabs()
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            t.background = if (i == selected) GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(theme.keyFn)
            } else null
        }
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
                setOnClickListener { onEmoji(emoji) }
            }
            suggestionRow.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        abc.setTextColor(t.text)
        del.setColorFilter(t.text)
        for (i in 0 until suggestionRow.childCount) {
            (suggestionRow.getChildAt(i) as? TextView)?.setTextColor(t.text)
        }
        paintTabs()
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
