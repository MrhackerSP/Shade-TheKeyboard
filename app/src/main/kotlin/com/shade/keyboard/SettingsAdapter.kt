package com.shade.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.shade.keyboard.keypresseffect.PressEffectPlayer
import java.util.Locale

// ============================================================================
// Settings adapter — Gboard-style.
//
// One RecyclerView that shows either
//   • the HOME: grouped category cards (Languages / Preferences / Theme …), or
//   • one PAGE: labelled sub-sections of rounded cards (switches, sliders …), or
//   • SEARCH results: matching rows from every page in a single card group.
//
// Rows that sit next to each other form one card group: the first row has big
// rounded top corners, the last row big rounded bottom corners, the rows in
// between only slightly rounded corners, separated by a 2dp gap (see CardStyle).
// ============================================================================

/** Where a row sits inside its card group. */
internal enum class CardPos { SINGLE, FIRST, MIDDLE, LAST }

/** Gboard-style card backgrounds: big outer corners, small inner corners, ripple. */
internal object CardStyle {
    private const val OUTER_DP = 26f
    private const val INNER_DP = 5f
    private const val GAP_DP = 2f

    private fun radii(ctx: Context, pos: CardPos): FloatArray {
        val d = ctx.resources.displayMetrics.density
        val outer = OUTER_DP * d
        val inner = INNER_DP * d
        val top = if (pos == CardPos.SINGLE || pos == CardPos.FIRST) outer else inner
        val bottom = if (pos == CardPos.SINGLE || pos == CardPos.LAST) outer else inner
        return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    }

    private fun ripple(ctx: Context, fill: Int, radii: FloatArray): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadii = radii
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = radii
        }
        return RippleDrawable(ColorStateList.valueOf(ctx.getColor(R.color.noir_ripple)), shape, mask)
    }

    /** Card background for a row at [pos]; the 2dp gap to the next row is applied here too. */
    fun card(view: View, pos: CardPos) {
        val ctx = view.context
        view.background = ripple(ctx, ctx.getColor(R.color.noir_card), radii(ctx, pos))
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val gap = if (pos == CardPos.SINGLE || pos == CardPos.LAST) 0
        else (GAP_DP * ctx.resources.displayMetrics.density).toInt()
        if (lp.bottomMargin != gap) {
            lp.bottomMargin = gap
            view.layoutParams = lp
        }
    }

    /** Fully rounded pill background (the hero / master switch row). */
    fun pill(view: View, fill: Int) {
        val ctx = view.context
        val r = 40f * ctx.resources.displayMetrics.density
        view.background = ripple(ctx, fill, floatArrayOf(r, r, r, r, r, r, r, r))
    }
}

internal class SettingsAdapter(
    private val ctx: SettingsActivity,
    private val prefs: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class Title(val textRes: Int) : Row()
        class Label(val textRes: Int) : Row()
        class Category(val page: SettingsCatalog.Page, val pos: CardPos) : Row()
        class Child(val item: SettingsItem, val pos: CardPos) : Row()
        object Gap : Row()
        class Empty(val textRes: Int) : Row()
    }

    private val rows = ArrayList<Row>()
    private var searching = false

    /** [HOME], or the id of the SettingsCatalog page being shown. */
    var currentPageId: Int = HOME
        private set

    val isHome: Boolean get() = currentPageId == HOME

    init {
        buildRows()
    }

    // ── Navigation ───────────────────────────────────────────────────────

    fun showHome() {
        currentPageId = HOME
        rebuild()
    }

    fun showPage(pageId: Int) {
        if (SettingsCatalog.page(pageId) == null) return
        searching = false
        currentPageId = pageId
        rebuild()
    }

    fun currentPage(): SettingsCatalog.Page? = SettingsCatalog.page(currentPageId)

    // ── Search ───────────────────────────────────────────────────────────

    fun applySearch(query: String) {
        val q = query.trim()
        rows.clear()
        if (q.isEmpty()) {
            searching = false
            buildRows()
        } else {
            searching = true
            val results = SettingsCatalog.search(q) { ctx.getString(it) }
            if (results.isEmpty()) {
                rows.add(Row.Empty(R.string.info_no_results))
            } else {
                // Search results are one card group, but hero rows keep their pill look.
                results.forEachIndexed { i, item ->
                    rows.add(Row.Child(item, posOf(i, results.size)))
                }
            }
        }
        notifyDataSetChanged()
    }

    fun rebuild() {
        rows.clear()
        if (searching && isHome) {
            // Re-run the current query so external pref changes show up.
            applySearch(searchInputQuery())
            return
        }
        searching = false
        buildRows()
        notifyDataSetChanged()
    }

    private fun searchInputQuery(): String =
        ctx.findViewById<EditText>(R.id.noir_search_input)?.text?.toString().orEmpty()

    // ── Row construction ─────────────────────────────────────────────────

    private fun buildRows() {
        val page = currentPage()
        if (page == null) buildHome() else buildPage(page)
    }

    private fun buildHome() {
        SettingsCatalog.homeGroups.forEachIndexed { g, group ->
            val pages = group.mapNotNull { SettingsCatalog.page(it) }
            pages.forEachIndexed { i, page -> rows.add(Row.Category(page, posOf(i, pages.size))) }
            if (g != SettingsCatalog.homeGroups.lastIndex) rows.add(Row.Gap)
        }
    }

    private fun buildPage(page: SettingsCatalog.Page) {
        if (page.largeTitle) rows.add(Row.Title(page.titleRes))
        for (section in page.sections) {
            if (section.titleRes != 0) rows.add(Row.Label(section.titleRes))
            // Consecutive card rows form one group; info text and the hero pill stand alone.
            val group = ArrayList<SettingsItem>()
            fun flush() {
                group.forEachIndexed { i, member -> rows.add(Row.Child(member, posOf(i, group.size))) }
                group.clear()
            }
            for (item in section.items) {
                if (isStandalone(item)) {
                    flush()
                    rows.add(Row.Child(item, CardPos.SINGLE))
                } else group.add(item)
            }
            flush()
        }
    }

    private fun isStandalone(item: SettingsItem): Boolean =
        item is SettingsItem.Info || (item is SettingsItem.Toggle && item.hero)

    private fun posOf(index: Int, size: Int): CardPos = when {
        size <= 1 -> CardPos.SINGLE
        index == 0 -> CardPos.FIRST
        index == size - 1 -> CardPos.LAST
        else -> CardPos.MIDDLE
    }

    /** The item rendered at [position], or null for structural rows. */
    internal fun itemAt(position: Int): SettingsItem? =
        (rows.getOrNull(position) as? Row.Child)?.item

    /** The category shown at [position] on the home screen, or null. */
    internal fun pageAt(position: Int): SettingsCatalog.Page? =
        (rows.getOrNull(position) as? Row.Category)?.page

    /** True when the row at [position] is the large page title. */
    internal fun isTitleAt(position: Int): Boolean = rows.getOrNull(position) is Row.Title

    // ── RecyclerView plumbing ────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int = when (val r = rows[position]) {
        is Row.Title -> TYPE_TITLE
        is Row.Label -> TYPE_LABEL
        is Row.Category -> TYPE_CATEGORY
        is Row.Gap -> TYPE_GAP
        is Row.Empty -> TYPE_EMPTY
        is Row.Child -> when (val item = r.item) {
            is SettingsItem.Toggle -> if (item.hero) TYPE_HERO else TYPE_TOGGLE
            is SettingsItem.Slider -> TYPE_SLIDER
            is SettingsItem.Action -> TYPE_ACTION
            is SettingsItem.Choice -> TYPE_CHOICE
            is SettingsItem.Color -> TYPE_COLOR
            is SettingsItem.Info -> TYPE_INFO
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(ctx)
        return when (viewType) {
            TYPE_TITLE -> TitleVH(inf.inflate(R.layout.item_noir_title, parent, false))
            TYPE_LABEL -> LabelVH(inf.inflate(R.layout.item_noir_label, parent, false))
            TYPE_CATEGORY -> CategoryVH(inf.inflate(R.layout.item_noir_category, parent, false))
            TYPE_TOGGLE -> ToggleVH(inf.inflate(R.layout.item_noir_toggle, parent, false), hero = false)
            TYPE_HERO -> ToggleVH(inf.inflate(R.layout.item_noir_hero, parent, false), hero = true)
            TYPE_SLIDER -> SliderVH(inf.inflate(R.layout.item_noir_slider, parent, false))
            TYPE_ACTION -> ActionVH(inf.inflate(R.layout.item_noir_action, parent, false))
            TYPE_CHOICE -> ChoiceVH(inf.inflate(R.layout.item_noir_choice, parent, false))
            TYPE_COLOR -> ColorVH(inf.inflate(R.layout.item_noir_color, parent, false))
            TYPE_INFO, TYPE_EMPTY -> InfoVH(inf.inflate(R.layout.item_noir_info, parent, false))
            TYPE_GAP -> GapVH(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (16f * parent.resources.displayMetrics.density).toInt()
                )
            })
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Title -> (holder as TitleVH).bind(row.textRes)
            is Row.Label -> (holder as LabelVH).bind(row.textRes)
            is Row.Gap -> Unit
            is Row.Empty -> (holder as InfoVH).bind(row.textRes)
            is Row.Category -> (holder as CategoryVH).bind(
                row.page, row.pos, summaryFor(row.page), ctx
            )
            is Row.Child -> when (val item = row.item) {
                is SettingsItem.Toggle -> (holder as ToggleVH).bind(item, prefs, row.pos)
                is SettingsItem.Slider -> (holder as SliderVH).bind(item, prefs, row.pos)
                is SettingsItem.Action -> (holder as ActionVH).bind(item, prefs, row.pos)
                is SettingsItem.Choice -> (holder as ChoiceVH).bind(item, prefs, row.pos)
                is SettingsItem.Color -> (holder as ColorVH).bind(item, prefs, row.pos)
                is SettingsItem.Info -> (holder as InfoVH).bind(item.textRes)
            }
        }
    }

    /** Home-row summary line; only Languages has one (like Gboard). */
    private fun summaryFor(page: SettingsCatalog.Page): String? {
        if (page.id != SettingsCatalog.PAGE_LANGUAGES) return null
        val dicts = prefs.getStringSet("dictionaries", setOf("en")) ?: setOf("en")
        val names = listOf(
            "en" to R.string.lang_en,
            "hi" to R.string.lang_hi,
            "hinglish" to R.string.lang_hinglish
        ).filter { it.first in dicts }.map { ctx.getString(it.second) }
            .ifEmpty { listOf(ctx.getString(R.string.lang_en)) }
        val layout = prefs.getString("layout", "qwerty")
        val layoutRes = SettingsCatalog.flat()
            .filterIsInstance<SettingsItem.Choice>()
            .firstOrNull { it.key == "layout" }
            ?.options?.firstOrNull { it.first == layout }?.second
        val suffix = if (layoutRes != null) " (${ctx.getString(layoutRes)})" else ""
        return names.joinToString(", ") + suffix
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    internal class TitleVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(textRes: Int) {
            (itemView as TextView).text = itemView.context.getString(textRes)
            ViewCompat.setAccessibilityHeading(itemView, true)
        }
    }

    internal class LabelVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(textRes: Int) {
            (itemView as TextView).text = itemView.context.getString(textRes)
            ViewCompat.setAccessibilityHeading(itemView, true)
        }
    }

    internal class GapVH(v: View) : RecyclerView.ViewHolder(v)

    internal class CategoryVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(page: SettingsCatalog.Page, pos: CardPos, summary: String?, ctx: SettingsActivity) {
            val c = itemView.context
            itemView.findViewById<ImageView>(R.id.noir_cat_icon).setImageResource(page.iconRes)
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(page.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.noir_subtitle)
            if (!summary.isNullOrEmpty()) {
                sub.text = summary
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE
            CardStyle.card(itemView, pos)
            itemView.setOnClickListener { ctx.openPage(page.id) }
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            })
        }
    }

    /** A switch row (normal card row, or the full-width hero pill when [hero]). */
    internal class ToggleVH(v: View, private val hero: Boolean) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Toggle, prefs: SharedPreferences, pos: CardPos) {
            val c = itemView.context
            val title = itemView.findViewById<TextView>(R.id.noir_title)
            title.text = c.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.noir_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = c.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE

            val sw = itemView.findViewById<MaterialSwitch>(R.id.noir_switch)

            fun paintHero(checked: Boolean) {
                if (!hero) return
                val fg = if (checked) c.getColor(R.color.noir_on_button) else c.getColor(R.color.noir_text)
                val fgSub = if (checked) c.getColor(R.color.noir_on_button) else c.getColor(R.color.noir_text_secondary)
                title.setTextColor(fg)
                sub.setTextColor(fgSub)
                CardStyle.pill(itemView, c.getColor(if (checked) R.color.noir_hero_on else R.color.noir_card))
            }

            // Avoid listener feedback while restoring the checked state.
            sw.setOnCheckedChangeListener(null)
            val on = prefs.getBoolean(item.key, item.default)
            sw.isChecked = on
            if (hero) paintHero(on) else CardStyle.card(itemView, pos)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(item.key, checked).apply()
                paintHero(checked)
            }
            itemView.setOnClickListener { sw.isChecked = !sw.isChecked }
            // The switch itself is hidden from accessibility (the whole row is the
            // target), so the row must announce itself as a switch with its state.
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = sw.isChecked
                }
            })
        }
    }

    internal class SliderVH(v: View) : RecyclerView.ViewHolder(v) {

        /**
         * Vibration strength uses the stock keyboard's labels: "System default"
         * until the slider is first moved (the engine's default state — the OS
         * keyboard tap), then the engine's dual unit — "N ms" on legacy
         * devices, "N%" on haptic-primitive hardware.
         */
        private fun labelFor(item: SettingsItem.Slider, value: Int, customized: Boolean): String {
            if (item.key == "vibration_duration") {
                if (!customized) return itemView.context.getString(R.string.vibration_system_default)
                runCatching {
                    val label = PressEffectPlayer.get().formatVibrationLabel(value)
                    if (label.isNotEmpty()) return label
                }
            }
            return String.format(Locale.US, item.format, value)
        }

        fun bind(item: SettingsItem.Slider, prefs: SharedPreferences, pos: CardPos) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val valueTv = itemView.findViewById<TextView>(R.id.noir_value)
            val slider = itemView.findViewById<Slider>(R.id.noir_slider)

            // Detach the previous row's listeners FIRST: on a recycled holder,
            // assigning the new value below would otherwise fire the old item's
            // callbacks against this row.
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()

            slider.valueFrom = item.min.toFloat()
            slider.valueTo = item.max.toFloat()
            slider.stepSize = item.step.toFloat()
            // Material's Slider throws when the value is not aligned to a step
            // from valueFrom — align defensively so a legacy pref value can
            // never crash the bind.
            val raw = prefs.getInt(item.key, item.default)
                .coerceIn(item.min, item.max)
            val steps = Math.round((raw - item.min).toFloat() / item.step)
            val aligned = (item.min + steps * item.step).coerceIn(item.min, item.max)
            slider.value = aligned.toFloat()
            valueTv.text = labelFor(item, aligned, prefs.contains(item.key))

            var touching = false
            slider.addOnChangeListener { _, value, fromUser ->
                val v = value.toInt()
                // Dragging the slider is by definition choosing a custom
                // strength, so the label renders the engine's dual unit.
                valueTv.text = labelFor(item, v, true)
                if (item.key == "vibration_duration" && fromUser) {
                    // Live preview: each slider tick plays the effect immediately.
                    runCatching { PressEffectPlayer.get().playCustomVibration(v) }
                }
                // Touch drags save on release (below); a change that arrives with
                // no touch — TalkBack/D-pad adjustments — used to be lost entirely.
                if (fromUser && !touching) prefs.edit().putInt(item.key, v).apply()
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { touching = true }
                override fun onStopTrackingTouch(slider: Slider) {
                    touching = false
                    prefs.edit().putInt(item.key, slider.value.toInt()).apply()
                }
            })
            CardStyle.card(itemView, pos)
        }
    }

    internal class ActionVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Action, prefs: SharedPreferences, pos: CardPos) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.noir_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = c.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE
            CardStyle.card(itemView, pos)
            itemView.setOnClickListener { item.onClick(c, prefs) }
        }
    }

    /**
     * Gboard-style choice row: the title with the current value underneath;
     * tapping opens a single-choice picker (no inline segmented control).
     */
    internal class ChoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Choice, prefs: SharedPreferences, pos: CardPos) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val valueTv = itemView.findViewById<TextView>(R.id.noir_choice_value)

            // A stored value that is no longer an option (renamed/legacy pref)
            // used to leave NOTHING selected; fall back to the default.
            val current = prefs.getString(item.key, item.default)
                ?.takeIf { v -> item.options.any { it.first == v } } ?: item.default
            valueTv.text = labelOf(item, current)

            CardStyle.card(itemView, pos)
            itemView.setOnClickListener {
                val saved = prefs.getString(item.key, item.default)
                val now = item.options.indexOfFirst { it.first == saved }.coerceAtLeast(0)
                val labels = item.options.map { c.getString(it.second) }.toTypedArray()
                AlertDialog.Builder(c, R.style.ShadeDialogTheme)
                    .setTitle(c.getString(item.titleRes))
                    .setSingleChoiceItems(labels, now) { dialog, which ->
                        prefs.edit().putString(item.key, item.options[which].first).apply()
                        valueTv.text = labels[which]
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun labelOf(item: SettingsItem.Choice, value: String): String {
            val res = item.options.firstOrNull { it.first == value }?.second ?: item.options.first().second
            return itemView.context.getString(res)
        }
    }

    internal class ColorVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Color, prefs: SharedPreferences, pos: CardPos) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val swatch = itemView.findViewById<View>(R.id.noir_swatch)
            val valueTv = itemView.findViewById<TextView>(R.id.noir_color_value)
            val raw = prefs.getString(item.key, null)
            val shown: Int
            if (raw.isNullOrEmpty()) {
                shown = item.autoColor
                valueTv.text = c.getString(R.string.color_auto)
            } else {
                shown = ColorMath.parseHex(raw) ?: item.autoColor
                valueTv.text = ColorMath.formatHex(shown)
            }
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = c.dp(10f)
                setColor(shown)
                setStroke(c.dp(1f).toInt(), c.getColor(R.color.noir_outline))
            }
            CardStyle.card(itemView, pos)
            itemView.setOnClickListener {
                (c as? SettingsActivity)?.showColorPicker(item.key, item.titleRes, item.autoColor)
            }
        }
    }

    internal class InfoVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(textRes: Int) {
            (itemView as TextView).text = itemView.context.getString(textRes)
        }
    }

    companion object {
        /** [currentPageId] while the settings home is showing. */
        const val HOME = -1

        const val TYPE_TITLE = 0
        const val TYPE_LABEL = 1
        const val TYPE_CATEGORY = 2
        const val TYPE_TOGGLE = 3
        const val TYPE_HERO = 4
        const val TYPE_SLIDER = 5
        const val TYPE_ACTION = 6
        const val TYPE_CHOICE = 7
        const val TYPE_COLOR = 8
        const val TYPE_INFO = 9
        const val TYPE_GAP = 10
        const val TYPE_EMPTY = 11
    }
}

private fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
