package com.noircipher.shade

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Clipboard history, stored only on this device. Pinned entries survive "clear". */
class ClipboardStore(ctx: Context) {
    data class Clip(val text: String, val pinned: Boolean, val time: Long)

    private val prefs = ctx.getSharedPreferences("clip_history", Context.MODE_PRIVATE)

    fun load(): MutableList<Clip> {
        val out = ArrayList<Clip>()
        try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Clip(o.getString("t"), o.optBoolean("p"), o.optLong("ts")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(list: List<Clip>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.text).put("p", it.pinned).put("ts", it.time)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(text: String) {
        val list = load()
        val old = list.firstOrNull { it.text == text }
        list.removeAll { it.text == text }
        list.add(0, Clip(text, old?.pinned ?: false, System.currentTimeMillis()))
        var unpinned = 0
        save(list.filter { it.pinned || ++unpinned <= 40 })
    }

    fun togglePin(text: String) = save(load().map { if (it.text == text) it.copy(pinned = !it.pinned) else it })
    fun remove(text: String) = save(load().filter { it.text != text })
    fun clearUnpinned() = save(load().filter { it.pinned })
    fun clearAll() = prefs.edit().remove("items").apply()
}

class ClipboardPanel(
    context: Context,
    private val store: ClipboardStore,
    private val onPaste: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private var clips: List<ClipboardStore.Clip> = emptyList()
    private var theme: KbTheme = Themes.LIGHT
    private val list = ListView(context)
    private val emptyView = TextView(context)
    private val abc = TextView(context)
    private val clear = TextView(context)
    private val del = ImageView(context)

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = clips.size
        override fun getItem(position: Int) = clips[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val clip = clips[position]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(6f), dp(2f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(theme.key)
                }
                setOnClickListener { onPaste(clip.text) }
            }
            val tv = TextView(context).apply {
                text = clip.text
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 14f
                setTextColor(theme.text)
            }
            row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = if (clip.pinned) "★" else "☆"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (clip.pinned) theme.accent else theme.hint)
                setOnClickListener { store.togglePin(clip.text); refresh() }
            }, LayoutParams(dp(40f), dp(40f)))
            row.addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(theme.hint)
                setOnClickListener { store.remove(clip.text); refresh() }
            }, LayoutParams(dp(40f), dp(40f)))
            return row
        }
    }

    init {
        orientation = VERTICAL
        list.adapter = adapter
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(6f)
        list.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        list.clipToPadding = false
        emptyView.text = context.getString(R.string.clip_empty)
        emptyView.gravity = Gravity.CENTER
        list.emptyView = emptyView
        val frame = FrameLayout(context)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        clear.text = context.getString(R.string.clip_clear)
        clear.gravity = Gravity.CENTER
        clear.textSize = 14f
        clear.setOnClickListener { store.clearUnpinned(); refresh() }
        bar.addView(clear, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
    }

    fun refresh() {
        clips = store.load()
        adapter.notifyDataSetChanged()
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        emptyView.setTextColor(t.hint)
        abc.setTextColor(t.text)
        clear.setTextColor(t.text)
        del.setColorFilter(t.text)
        adapter.notifyDataSetChanged()
    }
}
