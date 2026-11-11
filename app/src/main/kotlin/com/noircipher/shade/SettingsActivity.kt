package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

// ============================================================================
// Material 3 settings UI for Shade — inspired by Expressive Keyboard's
// card-based layout with a search bar, but built on traditional Android Views
// (RecyclerView + MaterialSwitch + Slider + MaterialButtonToggleGroup) instead
// of Jetpack Compose. All existing SharedPreferences keys are preserved.
//
// Architecture:
//  - SettingsActivity hosts a RecyclerView + a search bar.
//  - SettingsAdapter renders heterogeneous items (headers, category cards,
//    toggles, sliders, actions, choices, dividers, info cards, colors).
//  - SettingsTree defines which items appear on each page, and powers search.
//  - Navigation is a simple page stack inside the activity (no fragments).
//
// All pref keys match what ShadeService.applyPrefs() reads — no renames.
// ============================================================================

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: SettingsAdapter
    private lateinit var list: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var searchContainer: View
    private lateinit var titleView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSearchToggle: ImageButton

    private var currentPage = SettingsPage.HOME
    private var searchQuery: String = ""

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        PrefsMigration.run(prefs)

        setContentView(R.layout.activity_settings_new)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            insets
        }

        bindViews()
        adapter = SettingsAdapter(this, prefs) { page -> navigateTo(page) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        refreshPage()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (searchContainer.visibility == View.VISIBLE) {
            searchContainer.visibility = View.GONE
            searchInput.text.clear()
            searchQuery = ""
            adapter.applySearch("", currentPage)
        } else if (currentPage != SettingsPage.HOME) {
            navigateTo(SettingsPage.HOME)
        } else {
            super.onBackPressed()
        }
    }

    // ── View binding ───────────────────────────────────────────────────────

    private fun bindViews() {
        list = findViewById(R.id.settings_list)
        searchInput = findViewById(R.id.settings_search_input)
        searchContainer = findViewById(R.id.search_container)
        titleView = findViewById(R.id.settings_title)
        btnBack = findViewById(R.id.btn_back)
        btnSearchToggle = findViewById(R.id.btn_search_toggle)

        btnBack.setOnClickListener {
            if (currentPage != SettingsPage.HOME) navigateTo(SettingsPage.HOME)
            else finish()
        }
        btnSearchToggle.setOnClickListener { toggleSearch() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                adapter.applySearch(searchQuery, currentPage)
            }
        })
    }

    private fun toggleSearch() {
        if (searchContainer.visibility == View.VISIBLE) {
            searchContainer.visibility = View.GONE
            searchInput.text.clear()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } else {
            searchContainer.visibility = View.VISIBLE
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private fun navigateTo(page: SettingsPage) {
        currentPage = page
        searchContainer.visibility = View.GONE
        searchInput.text.clear()
        searchQuery = ""
        refreshPage()
        list.scrollToPosition(0)
    }

    private fun refreshPage() {
        titleView.text = getString(currentPage.titleRes)
        btnBack.visibility = if (currentPage == SettingsPage.HOME) View.GONE else View.VISIBLE
        adapter.applySearch(searchQuery, currentPage)
    }
}

// ============================================================================
// Pages — each has a title resource, subtitle, icon, and badge color index
// (cycled 0/1/2 → primaryContainer/secondaryContainer/tertiaryContainer).
// ============================================================================

enum class SettingsPage(
    val titleRes: Int,
    val subtitleRes: Int,
    val iconRes: Int,
    val badgeColorIndex: Int
) {
    HOME(R.string.settings_title, R.string.settings_subtitle, 0, 0),
    APPEARANCE(R.string.cat_appearance, R.string.cat_appearance_sub, R.drawable.ic_m3_palette, 0),
    TYPING(R.string.cat_typing, R.string.cat_typing_sub, R.drawable.ic_m3_keyboard, 1),
    LANGUAGES(R.string.cat_languages, R.string.cat_languages_sub, R.drawable.ic_settings_languages, 0),
    BACKUP(R.string.cat_backup, R.string.cat_backup_sub, R.drawable.ic_settings_backup, 2),
    ABOUT(R.string.cat_about, R.string.cat_about_sub, R.drawable.ic_settings_about, 1);
}

// ============================================================================
// Item types
// ============================================================================

private const val TYPE_HEADER = 0
private const val TYPE_CATEGORY = 1
private const val TYPE_TOGGLE = 2
private const val TYPE_SLIDER = 3
private const val TYPE_ACTION = 4
private const val TYPE_CHOICE = 5
private const val TYPE_DIVIDER = 6
private const val TYPE_INFO = 7
private const val TYPE_COLOR = 8

sealed class SettingsItem {
    abstract val type: Int

    data class Header(val titleRes: Int) : SettingsItem() { override val type = TYPE_HEADER }

    data class Category(val page: SettingsPage) : SettingsItem() { override val type = TYPE_CATEGORY }

    data class Toggle(
        val key: String,
        val titleRes: Int,
        val subtitleRes: Int = 0,
        val iconRes: Int = 0,
        val default: Boolean,
        val onToggle: ((Context, Boolean) -> Unit)? = null
    ) : SettingsItem() { override val type = TYPE_TOGGLE }

    data class Slider(
        val key: String,
        val titleRes: Int,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Int,
        val format: String = "%d",
        val onCommit: ((Context, Int) -> Unit)? = null
    ) : SettingsItem() { override val type = TYPE_SLIDER }

    data class Action(
        val titleRes: Int,
        val subtitleRes: Int = 0,
        val iconRes: Int = 0,
        val valueText: ((Context, SharedPreferences) -> String?)? = null,
        val onClick: (Context, SharedPreferences) -> Unit
    ) : SettingsItem() { override val type = TYPE_ACTION }

    /** A choice (segmented control). Each option is (prefValue, labelStringRes). */
    data class Choice(
        val key: String,
        val titleRes: Int,
        val options: List<Pair<String, Int>>,  // (value, stringRes)
        val default: String,
        val onSelect: ((Context, String) -> Unit)? = null
    ) : SettingsItem() { override val type = TYPE_CHOICE }

    object Divider : SettingsItem() { override val type = TYPE_DIVIDER }

    data class Info(val textRes: Int) : SettingsItem() { override val type = TYPE_INFO }

    data class Color(
        val key: String,
        val titleRes: Int,
        val autoColor: Int,
        val onClick: (Context, SharedPreferences) -> Unit
    ) : SettingsItem() { override val type = TYPE_COLOR }
}

// ============================================================================
// Adapter
// ============================================================================

class SettingsAdapter(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val onNavigate: (SettingsPage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<SettingsItem>()

    fun applySearch(query: String, page: SettingsPage) {
        items.clear()
        if (query.isNotEmpty()) {
            items.addAll(SettingsTree.search(query))
        } else {
            items.addAll(SettingsTree.build(page))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = items[position].type
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(ctx)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_settings_header, parent, false))
            TYPE_CATEGORY -> CategoryVH(inf.inflate(R.layout.item_settings_category, parent, false))
            TYPE_TOGGLE -> ToggleVH(inf.inflate(R.layout.item_settings_toggle, parent, false))
            TYPE_SLIDER -> SliderVH(inf.inflate(R.layout.item_settings_slider, parent, false))
            TYPE_ACTION -> ActionVH(inf.inflate(R.layout.item_settings_action, parent, false))
            TYPE_CHOICE -> ChoiceVH(inf.inflate(R.layout.item_settings_choice, parent, false))
            TYPE_DIVIDER -> DividerVH(inf.inflate(R.layout.item_settings_divider, parent, false))
            TYPE_INFO -> InfoVH(inf.inflate(R.layout.item_settings_info, parent, false))
            TYPE_COLOR -> ColorVH(inf.inflate(R.layout.item_settings_color, parent, false))
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderVH -> holder.bind(item as SettingsItem.Header)
            is CategoryVH -> holder.bind(item as SettingsItem.Category, onNavigate)
            is ToggleVH -> holder.bind(ctx, item as SettingsItem.Toggle, prefs)
            is SliderVH -> holder.bind(ctx, item as SettingsItem.Slider, prefs)
            is ActionVH -> holder.bind(ctx, item as SettingsItem.Action, prefs)
            is ChoiceVH -> holder.bind(ctx, item as SettingsItem.Choice, prefs)
            is DividerVH -> {}
            is InfoVH -> holder.bind(item as SettingsItem.Info)
            is ColorVH -> holder.bind(ctx, item as SettingsItem.Color, prefs)
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Header) {
            (itemView as TextView).text = itemView.context.getString(item.titleRes)
        }
    }

    class CategoryVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Category, onNavigate: (SettingsPage) -> Unit) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.category_title).text = c.getString(item.page.titleRes)
            itemView.findViewById<TextView>(R.id.category_subtitle).text = c.getString(item.page.subtitleRes)
            val badge = itemView.findViewById<FrameLayout>(R.id.category_icon_badge)
            val icon = itemView.findViewById<ImageView>(R.id.category_icon)
            icon.setImageResource(item.page.iconRes)
            val bgRes = when (item.page.badgeColorIndex % 3) {
                0 -> R.color.settings_badge_0_bg
                1 -> R.color.settings_badge_1_bg
                else -> R.color.settings_badge_2_bg
            }
            val fgRes = when (item.page.badgeColorIndex % 3) {
                0 -> R.color.settings_badge_0_fg
                1 -> R.color.settings_badge_1_fg
                else -> R.color.settings_badge_2_fg
            }
            badge.setBackgroundResource(bgRes)
            icon.setColorFilter(c.getColor(fgRes))
            itemView.setOnClickListener { onNavigate(item.page) }
        }
    }

    class ToggleVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(ctx: Context, item: SettingsItem.Toggle, prefs: SharedPreferences) {
            itemView.findViewById<TextView>(R.id.toggle_title).text = ctx.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.toggle_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = ctx.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE

            val iconBadge = itemView.findViewById<FrameLayout>(R.id.toggle_icon_badge)
            val icon = itemView.findViewById<ImageView>(R.id.toggle_icon)
            if (item.iconRes != 0) {
                icon.setImageResource(item.iconRes)
                iconBadge.visibility = View.VISIBLE
            } else iconBadge.visibility = View.GONE

            val sw = itemView.findViewById<MaterialSwitch>(R.id.toggle_switch)
            sw.isChecked = prefs.getBoolean(item.key, item.default)
            itemView.setOnClickListener {
                val newVal = !prefs.getBoolean(item.key, item.default)
                prefs.edit().putBoolean(item.key, newVal).apply()
                sw.isChecked = newVal
                item.onToggle?.invoke(ctx, newVal)
            }
        }
    }

    class SliderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(ctx: Context, item: SettingsItem.Slider, prefs: SharedPreferences) {
            itemView.findViewById<TextView>(R.id.slider_title).text = ctx.getString(item.titleRes)
            val valueTv = itemView.findViewById<TextView>(R.id.slider_value)
            val slider = itemView.findViewById<Slider>(R.id.slider_control)

            slider.valueFrom = item.min
            slider.valueTo = item.max
            slider.stepSize = item.step
            val cur = prefs.getInt(item.key, item.default).toFloat().coerceIn(item.min, item.max)
            slider.value = cur
            valueTv.text = String.format(item.format, cur.toInt())

            // Remove previous listeners to avoid stale callbacks on rebind
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()

            slider.addOnChangeListener { _, value, _ ->
                valueTv.text = String.format(item.format, value.toInt())
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.edit().putInt(item.key, slider.value.toInt()).apply()
                    item.onCommit?.invoke(ctx, slider.value.toInt())
                }
            })
        }
    }

    class ActionVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(ctx: Context, item: SettingsItem.Action, prefs: SharedPreferences) {
            itemView.findViewById<TextView>(R.id.action_title).text = ctx.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.action_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = ctx.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE

            val iconBadge = itemView.findViewById<FrameLayout>(R.id.action_icon_badge)
            val icon = itemView.findViewById<ImageView>(R.id.action_icon)
            if (item.iconRes != 0) {
                icon.setImageResource(item.iconRes)
                iconBadge.visibility = View.VISIBLE
            } else iconBadge.visibility = View.GONE

            val valueTv = itemView.findViewById<TextView>(R.id.action_value)
            val valueText = item.valueText?.invoke(ctx, prefs)
            if (valueText != null) {
                valueTv.text = valueText
                valueTv.visibility = View.VISIBLE
            } else valueTv.visibility = View.GONE

            itemView.setOnClickListener { item.onClick(ctx, prefs) }
        }
    }

    class ChoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(ctx: Context, item: SettingsItem.Choice, prefs: SharedPreferences) {
            itemView.findViewById<TextView>(R.id.choice_title).text = ctx.getString(item.titleRes)
            val group = itemView.findViewById<MaterialButtonToggleGroup>(R.id.choice_group)
            group.removeAllViews()
            group.clearOnButtonCheckedListeners()
            val current = prefs.getString(item.key, item.default)
            var checkedId = View.NO_ID
            item.options.forEachIndexed { idx, (value, labelRes) ->
                val btn = MaterialButton(ctx).apply {
                    text = ctx.getString(labelRes)
                    id = View.generateViewId()
                    if (value == current) {
                        isChecked = true
                        checkedId = id
                    }
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                group.addView(btn, lp)
            }
            group.addOnButtonCheckedListener { g, checkedId, isChecked ->
                if (isChecked) {
                    val idx = g.indexOfChild(g.findViewById(checkedId))
                    if (idx in item.options.indices) {
                        val (value, _) = item.options[idx]
                        prefs.edit().putString(item.key, value).apply()
                        item.onSelect?.invoke(ctx, value)
                    }
                }
            }
        }
    }

    class DividerVH(v: View) : RecyclerView.ViewHolder(v)
    class InfoVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Info) {
            itemView.findViewById<TextView>(R.id.info_text).text = itemView.context.getString(item.textRes)
        }
    }
    class ColorVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(ctx: Context, item: SettingsItem.Color, prefs: SharedPreferences) {
            itemView.findViewById<TextView>(R.id.color_title).text = ctx.getString(item.titleRes)
            val swatch = itemView.findViewById<View>(R.id.color_swatch)
            val sub = itemView.findViewById<TextView>(R.id.color_subtitle)
            val raw = prefs.getString(item.key, null)
            if (raw.isNullOrEmpty()) {
                swatch.setBackgroundColor(item.autoColor)
                sub.text = ctx.getString(R.string.color_auto)
                sub.visibility = View.VISIBLE
            } else {
                try {
                    swatch.setBackgroundColor(android.graphics.Color.parseColor(raw))
                    sub.text = raw
                    sub.visibility = View.VISIBLE
                } catch (_: Exception) {
                    swatch.setBackgroundColor(item.autoColor)
                    sub.text = ctx.getString(R.string.color_auto)
                    sub.visibility = View.VISIBLE
                }
            }
            itemView.setOnClickListener { item.onClick(ctx, prefs) }
        }
    }
}

// ============================================================================
// Settings tree — defines what appears on each page + powers search.
// This is a pure data object with no context dependency; string resource IDs
// are resolved by the ViewHolders at bind time.
// ============================================================================

object SettingsTree {

    fun build(page: SettingsPage): List<SettingsItem> = when (page) {
        SettingsPage.HOME -> buildHome()
        SettingsPage.APPEARANCE -> buildAppearance()
        SettingsPage.TYPING -> buildTyping()
        SettingsPage.LANGUAGES -> buildLanguages()
        SettingsPage.BACKUP -> buildBackup()
        SettingsPage.ABOUT -> buildAbout()
    }

    // ── Home ────────────────────────────────────────────────────────────

    private fun buildHome(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_appearance),
        SettingsItem.Category(SettingsPage.APPEARANCE),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_typing),
        SettingsItem.Category(SettingsPage.TYPING),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_languages),
        SettingsItem.Category(SettingsPage.LANGUAGES),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_backup),
        SettingsItem.Category(SettingsPage.BACKUP),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_about),
        SettingsItem.Category(SettingsPage.ABOUT)
    )

    // ── Appearance ──────────────────────────────────────────────────────

    private fun buildAppearance(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_theme),
        SettingsItem.Choice(
            key = "theme",
            titleRes = R.string.pref_theme,
            options = listOf(
                "system" to R.string.theme_system,
                "light" to R.string.theme_light,
                "dark" to R.string.theme_dark,
                "amoled" to R.string.theme_amoled
            ),
            default = "system"
        ),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_colors),
        SettingsItem.Color(
            key = "color_bg",
            titleRes = R.string.color_bg,
            autoColor = 0xFF1C1C1E.toInt()
        ) { _, _ -> /* TODO: color picker */ },
        SettingsItem.Divider,
        SettingsItem.Color(
            key = "color_key",
            titleRes = R.string.color_key,
            autoColor = 0xFF3C3C40.toInt()
        ) { _, _ -> /* TODO: color picker */ },
        SettingsItem.Divider,
        SettingsItem.Color(
            key = "color_accent",
            titleRes = R.string.color_accent,
            autoColor = 0xFF8AB4F8.toInt()
        ) { _, _ -> /* TODO: color picker */ },
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_keys),
        SettingsItem.Toggle(
            key = "key_borders",
            titleRes = R.string.pref_key_borders,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "show_hints",
            titleRes = R.string.pref_show_hints,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "popup_preview",
            titleRes = R.string.pref_popup_preview,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Slider(
            key = "key_height",
            titleRes = R.string.pref_key_height,
            min = 36f, max = 70f, step = 1f,
            default = 50,
            format = "%ddp"
        ),
        SettingsItem.Divider,
        SettingsItem.Choice(
            key = "one_handed",
            titleRes = R.string.pref_one_handed,
            options = listOf(
                "off" to R.string.one_handed_off,
                "left" to R.string.one_handed_left,
                "right" to R.string.one_handed_right
            ),
            default = "off"
        ),
        SettingsItem.Divider,
        SettingsItem.Slider(
            key = "one_handed_width",
            titleRes = R.string.pref_one_handed_width,
            min = 50f, max = 95f, step = 1f,
            default = 75,
            format = "%d%%"
        ),
        SettingsItem.Divider,
        SettingsItem.Choice(
            key = "split_keyboard",
            titleRes = R.string.pref_split_keyboard,
            options = listOf(
                "off" to R.string.split_off,
                "landscape" to R.string.split_landscape,
                "always" to R.string.split_always
            ),
            default = "off"
        ),
        SettingsItem.Divider,
        SettingsItem.Slider(
            key = "split_gap",
            titleRes = R.string.pref_split_gap,
            min = 0f, max = 120f, step = 5f,
            default = 0,
            format = "%ddp"
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "number_row",
            titleRes = R.string.pref_number_row,
            default = false
        )
    )

    // ── Typing ─────────────────────────────────────────────────────────

    private fun buildTyping(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_correction),
        SettingsItem.Toggle(
            key = "autocorrect",
            titleRes = R.string.pref_autocorrect,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "suggestions",
            titleRes = R.string.pref_suggestions,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "neural_predictions",
            titleRes = R.string.pref_neural,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "smart_correction",
            titleRes = R.string.pref_smart_correction,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "auto_caps",
            titleRes = R.string.pref_auto_caps,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "double_space",
            titleRes = R.string.pref_double_space,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "learn_words",
            titleRes = R.string.pref_learn_words,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "phrase_suggestions",
            titleRes = R.string.pref_phrase_sugg,
            subtitleRes = R.string.pref_phrase_sugg_sum,
            default = false
        ),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_glide),
        SettingsItem.Toggle(
            key = "glide_typing",
            titleRes = R.string.pref_glide_typing,
            subtitleRes = R.string.pref_glide_typing_sub,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "trackpad_mode",
            titleRes = R.string.pref_trackpad,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_feedback),
        SettingsItem.Toggle(
            key = "haptic",
            titleRes = R.string.pref_haptic,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Choice(
            key = "haptic_strength",
            titleRes = R.string.pref_haptic_strength,
            options = listOf(
                "light" to R.string.haptic_light,
                "medium" to R.string.haptic_medium,
                "strong" to R.string.haptic_strong
            ),
            default = "medium"
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "sound",
            titleRes = R.string.pref_sound,
            default = false
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "typing_burst",
            titleRes = R.string.pref_typing_burst,
            default = false
        ),
        SettingsItem.Divider,
        SettingsItem.Slider(
            key = "longpress_delay",
            titleRes = R.string.pref_longpress_delay,
            min = 200f, max = 700f, step = 10f,
            default = 300,
            format = "%dms"
        )
    )

    // ── Languages ──────────────────────────────────────────────────────

    private fun buildLanguages(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_dictionaries),
        SettingsItem.Toggle(
            key = "emoji_suggestions",
            titleRes = R.string.pref_emoji_suggestions,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "gif_enabled",
            titleRes = R.string.pref_gif,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "translator_enabled",
            titleRes = R.string.pref_translator,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "voice_input",
            titleRes = R.string.pref_voice_input,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Header(R.string.section_learning),
        SettingsItem.Toggle(
            key = "learning_adaptive",
            titleRes = R.string.pref_learning_adaptive,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "learning_appctx",
            titleRes = R.string.pref_learning_appctx,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "learning_ranker",
            titleRes = R.string.pref_learning_ranker,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "incognito",
            titleRes = R.string.pref_incognito,
            default = false
        )
    )

    // ── Backup ─────────────────────────────────────────────────────────

    private fun buildBackup(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_backup_data),
        SettingsItem.Toggle(
            key = "clipboard_history",
            titleRes = R.string.pref_clipboard_history,
            default = true
        ),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "contribute_data",
            titleRes = R.string.pref_contribute_data,
            default = false
        ),
        SettingsItem.Divider,
        SettingsItem.Info(R.string.info_backup),
        SettingsItem.Divider,
        SettingsItem.Action(
            titleRes = R.string.action_export,
            subtitleRes = R.string.action_export_sub
        ) { _, _ -> /* TODO: backup export */ },
        SettingsItem.Divider,
        SettingsItem.Action(
            titleRes = R.string.action_import,
            subtitleRes = R.string.action_import_sub
        ) { _, _ -> /* TODO: backup import */ },
        SettingsItem.Divider,
        SettingsItem.Action(
            titleRes = R.string.action_clear_learned,
            subtitleRes = R.string.action_clear_learned_sub
        ) { _, _ -> /* TODO: clear learned */ }
    )

    // ── About ──────────────────────────────────────────────────────────

    private fun buildAbout(): List<SettingsItem> = listOf(
        SettingsItem.Header(R.string.section_about_app),
        SettingsItem.Info(R.string.info_about),
        SettingsItem.Divider,
        SettingsItem.Toggle(
            key = "auto_update_check",
            titleRes = R.string.pref_auto_update,
            default = false
        ),
        SettingsItem.Divider,
        SettingsItem.Action(
            titleRes = R.string.action_check_update
        ) { _, _ -> /* TODO: update check */ },
        SettingsItem.Divider,
        SettingsItem.Action(
            titleRes = R.string.action_typing_stats
        ) { _, _ -> /* TODO: open typing stats */ }
    )

    // ── Search ─────────────────────────────────────────────────────────

    /**
     * Search index: each entry is (titleRes, keyword list, target page).
     * The keywords are lowercase English; the query is matched as a substring
     * against each keyword.
     */
    private val searchIndex: List<Triple<Int, List<String>, SettingsPage>> = listOf(
        // Appearance
        Triple(R.string.pref_theme, listOf("theme", "dark", "light", "amoled", "night"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_key_borders, listOf("border", "outline", "separator"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_show_hints, listOf("hint", "accent", "long press", "popup"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_popup_preview, listOf("popup", "preview", "preview"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_key_height, listOf("size", "height", "big", "small", "tall"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_one_handed, listOf("one hand", "one-handed", "left", "right", "width"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_one_handed_width, listOf("one hand", "width", "narrow", "thumb"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_split_keyboard, listOf("split", "tablet", "landscape", "always"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_split_gap, listOf("split", "gap", "width", "distance"), SettingsPage.APPEARANCE),
        Triple(R.string.pref_number_row, listOf("number", "digit", "123"), SettingsPage.APPEARANCE),
        Triple(R.string.color_bg, listOf("color", "background", "bg"), SettingsPage.APPEARANCE),
        Triple(R.string.color_key, listOf("color", "key"), SettingsPage.APPEARANCE),
        Triple(R.string.color_accent, listOf("color", "accent"), SettingsPage.APPEARANCE),
        // Typing
        Triple(R.string.pref_autocorrect, listOf("autocorrect", "fix", "typo", "correct"), SettingsPage.TYPING),
        Triple(R.string.pref_suggestions, listOf("suggestion", "predict", "t9"), SettingsPage.TYPING),
        Triple(R.string.pref_neural, listOf("neural", "ai", "onnx", "model"), SettingsPage.TYPING),
        Triple(R.string.pref_smart_correction, listOf("smart", "space", "capitalize", "period"), SettingsPage.TYPING),
        Triple(R.string.pref_auto_caps, listOf("capital", "caps", "uppercase"), SettingsPage.TYPING),
        Triple(R.string.pref_double_space, listOf("double", "period", "space"), SettingsPage.TYPING),
        Triple(R.string.pref_learn_words, listOf("learn", "word", "dictionary"), SettingsPage.TYPING),
        Triple(R.string.pref_phrase_sugg, listOf("phrase", "smart compose", "multi-word", "completion"), SettingsPage.TYPING),
        Triple(R.string.pref_glide_typing, listOf("glide", "swipe", "slide", "trail"), SettingsPage.TYPING),
        Triple(R.string.pref_trackpad, listOf("trackpad", "cursor", "space"), SettingsPage.TYPING),
        Triple(R.string.pref_haptic, listOf("vibrate", "haptic", "feedback"), SettingsPage.TYPING),
        Triple(R.string.pref_haptic_strength, listOf("vibrate", "haptic", "strong", "weak"), SettingsPage.TYPING),
        Triple(R.string.pref_sound, listOf("sound", "audio", "click"), SettingsPage.TYPING),
        Triple(R.string.pref_typing_burst, listOf("burst", "animation", "flash"), SettingsPage.TYPING),
        Triple(R.string.pref_longpress_delay, listOf("long press", "delay", "hold"), SettingsPage.TYPING),
        // Languages
        Triple(R.string.pref_emoji_suggestions, listOf("emoji", "suggestion"), SettingsPage.LANGUAGES),
        Triple(R.string.pref_gif, listOf("gif", "klipy", "sticker"), SettingsPage.LANGUAGES),
        Triple(R.string.pref_translator, listOf("translate", "translation"), SettingsPage.LANGUAGES),
        Triple(R.string.pref_voice_input, listOf("voice", "mic", "speech", "dictation"), SettingsPage.LANGUAGES),
        Triple(R.string.pref_learning_adaptive, listOf("adaptive", "learn", "frequent"), SettingsPage.LANGUAGES),
        Triple(R.string.pref_incognito, listOf("incognito", "private", "learning"), SettingsPage.LANGUAGES),
        // Backup
        Triple(R.string.pref_clipboard_history, listOf("clipboard", "history", "copy"), SettingsPage.BACKUP),
        Triple(R.string.pref_contribute_data, listOf("training", "contribute", "export"), SettingsPage.BACKUP),
        Triple(R.string.action_export, listOf("backup", "export", "save"), SettingsPage.BACKUP),
        Triple(R.string.action_import, listOf("backup", "import", "restore"), SettingsPage.BACKUP),
        Triple(R.string.action_clear_learned, listOf("clear", "reset", "learned", "delete"), SettingsPage.BACKUP),
        // About
        Triple(R.string.pref_auto_update, listOf("update", "auto", "check"), SettingsPage.ABOUT),
        Triple(R.string.action_check_update, listOf("update", "version", "new"), SettingsPage.ABOUT),
        Triple(R.string.action_typing_stats, listOf("stats", "typing", "analytics"), SettingsPage.ABOUT)
    )

    fun search(query: String): List<SettingsItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return buildHome()
        val results = searchIndex.filter { (_, keywords, _) ->
            keywords.any { it.contains(q) || q.contains(it) }
        }
        if (results.isEmpty()) return listOf(
            SettingsItem.Info(R.string.info_no_results)
        )
        val out = ArrayList<SettingsItem>()
        out += SettingsItem.Header(R.string.section_search_results)
        results.forEach { (_, _, page) ->
            out += SettingsItem.Category(page)
            out += SettingsItem.Divider
        }
        if (out.last() == SettingsItem.Divider) out.removeAt(out.lastIndex)
        return out
    }
}
