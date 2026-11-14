package com.noircipher.shade

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================================
// Shade settings — "Noir" redesign.
//
// One pitch-black screen, white type, hairline dividers, no cards, no page
// navigation: all sections are collapsible groups on a single list, and the
// search field is always pinned at the top. Color rows open an HSV picker
// sheet (saturation/brightness square + hue rail) built entirely in-project.
//
// All SharedPreferences keys and defaults match what ShadeService.applyPrefs()
// reads — see SettingsCatalog. The catalog additionally exposes prefs the old
// card UI never showed (color_keyfn, color_text, phrase suggestions, autofill
// chips, learning fine-tuning, key-repeat timing, typing insights).
// ============================================================================

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    internal lateinit var adapter: SettingsAdapter
    private lateinit var list: RecyclerView
    private lateinit var searchInput: EditText

    // Backup file pickers (SAF). The result flows into askBackupPassphrase(),
    // which runs Backup.export/import on IO.
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        PrefsMigration.run(prefs)

        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) askBackupPassphrase(isExport = true, uri)
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askBackupPassphrase(isExport = false, uri)
        }

        setContentView(R.layout.activity_settings_noir)

        list = findViewById(R.id.noir_list)
        searchInput = findViewById(R.id.noir_search_input)

        adapter = SettingsAdapter(this, prefs)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.applySearch(s?.toString().orEmpty())
            }
        })

        adapter.rebuild()
    }

    override fun onResume() {
        super.onResume()
        // An import may have rewritten prefs behind our back.
        adapter.rebuild()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ── Backup / restore / update actions ─────────────────────────────────

    /** Called from the catalog's action items; ctx there is this activity. */
    internal fun startExport() = exportLauncher.launch("shade-backup.shdbak")

    internal fun startImport() = importLauncher.launch(arrayOf("*/*"))

    internal fun openTypingStats() {
        startActivity(Intent(this, TypingStatsActivity::class.java))
    }

    /** Asks for the backup passphrase, then runs Backup.export / Backup.import on IO. */
    private fun askBackupPassphrase(isExport: Boolean, uri: Uri) {
        val input = EditText(this).apply {
            hint = getString(R.string.backup_pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(if (isExport) R.string.backup_pass_title_export else R.string.backup_pass_title_import))
            .setMessage(getString(R.string.backup_pass_warning))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = input.text.toString()
                if (isExport && pass.length < 8) {
                    Toast.makeText(this, R.string.backup_pass_short, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = try {
                        if (isExport) Backup.export(applicationContext, uri, pass)
                        else Backup.import(applicationContext, uri, pass)
                    } catch (t: Exception) { ShadeLog.w("SettingsActivity", "backup operation failed", t, once = true); false }
                    if (ok && !isExport) {
                        // The restore overwrote the learning stores' files on disk;
                        // the live singletons must re-read them or they would keep
                        // serving the pre-restore data until the next process start.
                        try { LearningDataCleaner.reloadAll(applicationContext) }
                        catch (e: Exception) { Log.w("SettingsActivity", "Failed to reload learning stores after restore", e) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            if (ok) R.string.backup_done else R.string.backup_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    internal fun confirmClearLearned() {
        AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(R.string.action_clear_learned))
            .setMessage(getString(R.string.action_clear_learned_sub))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        LearningDataCleaner.clearAll(applicationContext)
                        WordDictionary.clearLearned(applicationContext)
                        // Learned next-word bigrams live in their own file; clearing it makes
                        // the model re-seed from the bundled primer on the next start.
                        java.io.File(applicationContext.filesDir, "ngram.json").delete()
                    } catch (e: Exception) { Log.w("SettingsActivity", "Failed to clear learned data", e) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, R.string.backup_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun checkForUpdates() {
        lifecycleScope.launch {
            Updater.check(this@SettingsActivity)
            val info = Updater.available
            if (info != null) {
                AlertDialog.Builder(this@SettingsActivity, R.style.ShadeDialogTheme)
                    .setTitle(getString(R.string.update_available, info.version))
                    .setMessage(info.notes.ifBlank { null })
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        lifecycleScope.launch {
                            Updater.downloadAndInstall(this@SettingsActivity, info)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                val msg = Updater.error ?: if (Updater.upToDate) getString(R.string.update_uptodate)
                else getString(R.string.update_failed)
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Color picker sheet ─────────────────────────────────────────────────

    /**
     * Bottom-anchored HSV picker for the catalog's color rows. Shows the
     * current color in a preview bar, a saturation/brightness square, a hue
     * rail, a preset grid (the row's auto color first so "back to default" is
     * one tap) and a hex field, all kept in sync. Saving stores a normalized
     * "#RRGGBB" under [key] — the same keys Themes.custom() reads. Custom
     * colors only take effect when the theme is "custom", so picking a color
     * also switches the theme (with a toast) instead of silently doing nothing.
     */
    internal fun showColorPicker(key: String, titleRes: Int, autoColor: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<View>(R.id.picker_preview)
        val previewHex = view.findViewById<TextView>(R.id.picker_preview_hex)
        val picker = view.findViewById<ColorPickerView>(R.id.color_picker_view)
        val hexInput = view.findViewById<EditText>(R.id.picker_hex)
        val presetsGrid = view.findViewById<GridLayout>(R.id.picker_presets)
        val btnReset = view.findViewById<TextView>(R.id.picker_reset)

        view.findViewById<TextView>(R.id.picker_title).text = getString(titleRes)

        val savedRaw = prefs.getString(key, null)
        val saved = savedRaw?.let { ColorMath.parseHex(it) }
        val initialHsv = ColorMath.rgbToHsv(saved ?: autoColor)
        picker.setColor(initialHsv[0], initialHsv[1], initialHsv[2])

        // Guards against a feedback loop between the picker, the hex field and
        // the preview: only one of them drives at a time.
        var syncing = false

        fun renderPreview() {
            val c = picker.currentColor()
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(c)
            }
            previewHex.text = ColorMath.formatHex(c)
            previewHex.setTextColor(ColorMath.contrastTextColor(c))
        }

        picker.onColorChanged = { h, s, v ->
            if (!syncing) {
                syncing = true
                hexInput.setText(ColorMath.formatHex(ColorMath.hsvToRgb(h, s, v)))
                syncing = false
            }
            renderPreview()
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) {
                    ColorMath.parseHex(s?.toString())?.let { c ->
                        val hsv = ColorMath.rgbToHsv(c)
                        picker.setColor(hsv[0], hsv[1], hsv[2])
                    }
                }
                renderPreview()
            }
        })
        renderPreview()

        // Preset grid: the auto color first, then monochrome anchors and a few
        // useful hues for custom keyboard colors.
        val palette = linkedSetOf(
            autoColor,
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(), 0xFF3C3C40.toInt(),
            0xFF48484A.toInt(), 0xFF8E8E93.toInt(), 0xFFC7C7CC.toInt(),
            0xFFEDEDED.toInt(),
            0xFF8AB4F8.toInt(), 0xFF4285F4.toInt(), 0xFF7C4DFF.toInt(),
            0xFF34A853.toInt(), 0xFFF9AB2B.toInt(), 0xFFF28B82.toInt()
        )
        val cellPx = dp(40f).toInt()
        val dotPx = dp(34f).toInt()
        for (c in palette) {
            val cell = FrameLayout(this)
            cell.setPadding(dp(5f).toInt(), dp(5f).toInt(), dp(5f).toInt(), dp(5f).toInt())
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(dp(1f).toInt(), 0xFF2E2E2E.toInt())
            }
            cell.addView(dot, FrameLayout.LayoutParams(dotPx, dotPx, Gravity.CENTER))
            cell.setOnClickListener {
                syncing = true
                hexInput.setText(ColorMath.formatHex(c))
                val hsv = ColorMath.rgbToHsv(c)
                picker.setColor(hsv[0], hsv[1], hsv[2])
                syncing = false
                renderPreview()
            }
            presetsGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellPx
                height = cellPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.SheetDialogAnimation)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.6f }
        }

        btnReset.setOnClickListener {
            // "Auto" = clear the override; Themes falls back to the active base theme.
            prefs.edit().remove(key).apply()
            adapter.rebuild()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.picker_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.picker_apply).setOnClickListener {
            val norm = ColorMath.normalizeHex(hexInput.text?.toString())
            if (norm == null) {
                Toast.makeText(this, R.string.color_invalid_hex, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ed = prefs.edit()
            ed.putString(key, norm)
            if (prefs.getString("theme", "system") != "custom") {
                ed.putString("theme", "custom")
                Toast.makeText(this, R.string.color_theme_switched, Toast.LENGTH_SHORT).show()
            }
            ed.apply()
            adapter.rebuild()
            dialog.dismiss()
        }
        dialog.show()
    }
}

// ============================================================================
// Adapter — one flat RecyclerView with a hero header, collapsible section
// headers and child rows. Search swaps the whole list for matching items.
// ============================================================================

internal class SettingsAdapter(
    private val ctx: SettingsActivity,
    private val prefs: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        object Hero : Row()
        class Section(val def: SettingsCatalog.Section) : Row()
        class Child(val def: SettingsCatalog.Section, val item: SettingsItem, val isLast: Boolean) : Row()
        class Empty(val textRes: Int) : Row()
    }

    private val rows = ArrayList<Row>()
    private val collapsed = HashSet<Int>()
    private var searching = false

    internal val TYPE_SECTION = 0
    internal val TYPE_TOGGLE = 1
    internal val TYPE_SLIDER = 2
    internal val TYPE_ACTION = 3
    internal val TYPE_CHOICE = 4
    internal val TYPE_COLOR = 5
    internal val TYPE_INFO = 6
    internal val TYPE_HERO = 7
    internal val TYPE_EMPTY = 8

    init {
        rebuild()
    }

    // ── State transitions ────────────────────────────────────────────────

    fun applySearch(query: String) {
        val q = query.trim()
        rows.clear()
        if (q.isEmpty()) {
            searching = false
            rows.add(Row.Hero)
            appendCatalog()
        } else {
            searching = true
            val results = SettingsCatalog.search(q)
            if (results.isEmpty()) {
                rows.add(Row.Empty(R.string.info_no_results))
            } else {
                results.forEachIndexed { i, item ->
                    rows.add(Row.Child(SettingsCatalog.sections.first(), item, isLast = i == results.lastIndex))
                }
            }
        }
        notifyDataSetChanged()
    }

    fun rebuild() {
        rows.clear()
        if (searching) {
            // Re-run the current query so external pref changes show up.
            applySearch(searchInputQuery())
            return
        }
        rows.add(Row.Hero)
        appendCatalog()
        notifyDataSetChanged()
    }

    private fun searchInputQuery(): String =
        ctx.findViewById<EditText>(R.id.noir_search_input)?.text?.toString().orEmpty()

    private fun appendCatalog() {
        for (section in SettingsCatalog.sections) {
            rows.add(Row.Section(section))
            if (section.id !in collapsed) appendChildren(section)
        }
    }

    private fun childrenOf(section: SettingsCatalog.Section): List<Row.Child> =
        section.items.mapIndexed { i, item ->
            Row.Child(section, item, isLast = i == section.items.lastIndex)
        }

    private fun appendChildren(section: SettingsCatalog.Section) {
        rows.addAll(childrenOf(section))
    }

    /** Collapses a section, removing its children with a range notification. */
    fun collapse(sectionId: Int) {
        val headerPos = rows.indexOfFirst { it is Row.Section && it.def.id == sectionId }
        if (headerPos < 0) return
        var n = 0
        while (headerPos + 1 + n < rows.size && rows[headerPos + 1 + n] is Row.Child) n++
        if (n == 0) return
        collapsed.add(sectionId)
        repeat(n) { rows.removeAt(headerPos + 1) }
        notifyItemRangeRemoved(headerPos + 1, n)
        notifyItemChanged(headerPos)
    }

    /** Expands a section, inserting its children with a range notification. */
    fun expand(sectionId: Int) {
        val headerPos = rows.indexOfFirst { it is Row.Section && it.def.id == sectionId }
        if (headerPos < 0) return
        val section = (rows[headerPos] as Row.Section).def
        if (section.id !in collapsed) return
        collapsed.remove(sectionId)
        val children = childrenOf(section)
        rows.addAll(headerPos + 1, children)
        notifyItemRangeInserted(headerPos + 1, children.size)
        notifyItemChanged(headerPos)
    }

    fun toggleSection(sectionId: Int) {
        if (sectionId in collapsed) expand(sectionId) else collapse(sectionId)
    }

    fun isSectionCollapsed(sectionId: Int): Boolean = sectionId in collapsed

    /** The item rendered at [position], or null for structural rows. */
    internal fun itemAt(position: Int): SettingsItem? =
        (rows.getOrNull(position) as? Row.Child)?.item

    fun sectionAt(position: Int): SettingsCatalog.Section? =
        (rows.getOrNull(position) as? Row.Section)?.def

    // ── RecyclerView plumbing ────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int = when (val r = rows[position]) {
        is Row.Hero -> TYPE_HERO
        is Row.Section -> TYPE_SECTION
        is Row.Empty -> TYPE_EMPTY
        is Row.Child -> when (r.item) {
            is SettingsItem.Toggle -> TYPE_TOGGLE
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
            TYPE_HERO -> HeroVH(inf.inflate(R.layout.item_noir_hero, parent, false))
            TYPE_SECTION -> SectionVH(inf.inflate(R.layout.item_noir_section, parent, false))
            TYPE_TOGGLE -> ToggleVH(inf.inflate(R.layout.item_noir_toggle, parent, false))
            TYPE_SLIDER -> SliderVH(inf.inflate(R.layout.item_noir_slider, parent, false))
            TYPE_ACTION -> ActionVH(inf.inflate(R.layout.item_noir_action, parent, false))
            TYPE_CHOICE -> ChoiceVH(inf.inflate(R.layout.item_noir_choice, parent, false))
            TYPE_COLOR -> ColorVH(inf.inflate(R.layout.item_noir_color, parent, false))
            TYPE_INFO -> InfoVH(inf.inflate(R.layout.item_noir_info, parent, false))
            TYPE_EMPTY -> InfoVH(inf.inflate(R.layout.item_noir_info, parent, false))
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Hero -> (holder as HeroVH).bind()
            is Row.Section -> (holder as SectionVH).bind(row.def, row.def.id in collapsed) { id ->
                toggleSection(id)
            }
            is Row.Empty -> (holder as InfoVH).bind(row.textRes)
            is Row.Child -> when (val item = row.item) {
                is SettingsItem.Toggle -> (holder as ToggleVH).bind(item, prefs, row.isLast)
                is SettingsItem.Slider -> (holder as SliderVH).bind(item, prefs, row.isLast)
                is SettingsItem.Action -> (holder as ActionVH).bind(item, prefs, row.isLast)
                is SettingsItem.Choice -> (holder as ChoiceVH).bind(item, prefs, row.isLast)
                is SettingsItem.Color -> (holder as ColorVH).bind(item, prefs, row.isLast)
                is SettingsItem.Info -> (holder as InfoVH).bind(item.textRes, row.isLast)
            }
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    internal class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind() {
            val c = itemView.context
            val sub = itemView.findViewById<TextView>(R.id.noir_hero_sub)
            val version = try {
                c.packageManager.getPackageInfo(c.packageName, 0).versionName
            } catch (t: Exception) { ShadeLog.w("SettingsActivity", "version lookup failed", t, once = true); null }
            sub.text = if (version.isNullOrBlank()) c.getString(R.string.settings_subtitle)
            else "${c.getString(R.string.settings_subtitle)}  ·  v$version"
        }
    }

    internal class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(def: SettingsCatalog.Section, isCollapsed: Boolean, onToggle: (Int) -> Unit) {
            itemView.findViewById<TextView>(R.id.noir_section_title).text =
                itemView.context.getString(def.titleRes)
            val arrow = itemView.findViewById<View>(R.id.noir_section_arrow)
            arrow.rotation = if (isCollapsed) 0f else 90f
            itemView.setOnClickListener { onToggle(def.id) }
        }
    }

    internal class ToggleVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Toggle, prefs: SharedPreferences, isLast: Boolean) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.noir_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = c.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE

            val sw = itemView.findViewById<MaterialSwitch>(R.id.noir_switch)
            // Avoid listener feedback while restoring the checked state.
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = prefs.getBoolean(item.key, item.default)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(item.key, checked).apply()
            }
            itemView.setOnClickListener { sw.isChecked = !sw.isChecked }
            itemView.bindDivider(isLast)
        }
    }

    internal class SliderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Slider, prefs: SharedPreferences, isLast: Boolean) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val valueTv = itemView.findViewById<TextView>(R.id.noir_value)
            val slider = itemView.findViewById<Slider>(R.id.noir_slider)

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
            valueTv.text = String.format(Locale.US, item.format, aligned)

            // Remove previous listeners to avoid stale callbacks on rebind.
            slider.clearOnChangeListeners()
            slider.clearOnSliderTouchListeners()

            slider.addOnChangeListener { _, value, fromUser ->
                valueTv.text = String.format(Locale.US, item.format, value.toInt())
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.edit().putInt(item.key, slider.value.toInt()).apply()
                }
            })
            itemView.bindDivider(isLast)
        }
    }

    internal class ActionVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Action, prefs: SharedPreferences, isLast: Boolean) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val sub = itemView.findViewById<TextView>(R.id.noir_subtitle)
            if (item.subtitleRes != 0) {
                sub.text = c.getString(item.subtitleRes)
                sub.visibility = View.VISIBLE
            } else sub.visibility = View.GONE
            itemView.setOnClickListener { item.onClick(c, prefs) }
            itemView.bindDivider(isLast)
        }
    }

    internal class ChoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Choice, prefs: SharedPreferences, isLast: Boolean) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val group = itemView.findViewById<MaterialButtonToggleGroup>(R.id.noir_group)
            group.removeAllViews()
            group.clearOnButtonCheckedListeners()
            // singleSelection is FALSE by default in Material 1.12: without this, tapping a new
            // option leaves the old one checked, and tapping the checked one deselects it.
            group.isSingleSelection = true
            group.isSelectionRequired = true

            val current = prefs.getString(item.key, item.default)
            var checkedId = View.NO_ID
            item.options.forEach { (value, labelRes) ->
                val btn = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = c.getString(labelRes)
                    id = View.generateViewId()
                    isAllCaps = false
                    textSize = 14f
                    stateListAnimator = null
                    insetTop = 0
                    insetBottom = 0
                    strokeWidth = c.dp(1f).toInt()
                    strokeColor = ColorStateList.valueOf(0xFF2E2E2E.toInt())
                    rippleColor = ColorStateList.valueOf(0x33FFFFFF.toInt())
                    backgroundTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(0xFFFFFFFF.toInt(), 0x00000000)
                    )
                    setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
                        )
                    )
                    minimumHeight = c.dp(44f).toInt()
                    if (value == current) {
                        isChecked = true
                        checkedId = id
                    }
                }
                group.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            group.addOnButtonCheckedListener { g, checkedId, isChecked ->
                if (isChecked) {
                    val idx = g.indexOfChild(g.findViewById(checkedId))
                    if (idx in item.options.indices) {
                        val (value, _) = item.options[idx]
                        prefs.edit().putString(item.key, value).apply()
                    }
                }
            }
            itemView.bindDivider(isLast)
        }
    }

    internal class ColorVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(item: SettingsItem.Color, prefs: SharedPreferences, isLast: Boolean) {
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
                cornerRadius = c.dp(8f)
                setColor(shown)
                setStroke(c.dp(1f).toInt(), 0xFF2E2E2E.toInt())
            }
            itemView.setOnClickListener {
                (c as? SettingsActivity)?.showColorPicker(item.key, item.titleRes, item.autoColor)
            }
            itemView.bindDivider(isLast)
        }
    }

    internal class InfoVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(textRes: Int, isLast: Boolean = true) {
            itemView.findViewById<TextView>(R.id.noir_info_text).text =
                itemView.context.getString(textRes)
            itemView.bindDivider(isLast)
        }
    }

}

// File-private top-level extensions: the settings item view-holders above are nested
// (non-inner) classes, which cannot call the activity's private members. Declaring them
// here makes them visible to every class in this file without widening access.
private fun View.bindDivider(isLast: Boolean) {
    findViewById<View>(R.id.noir_divider).visibility =
        if (isLast) View.GONE else View.VISIBLE
}

private fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
