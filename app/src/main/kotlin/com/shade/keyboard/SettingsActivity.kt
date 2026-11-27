package com.shade.keyboard

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.lifecycle.lifecycleScope
import com.shade.keyboard.gif.GifConfig
import com.shade.keyboard.keypresseffect.PressEffectPlayer
import com.shade.keyboard.migration.DictionaryImport
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ============================================================================
// Shade settings — "Noir" redesign.
//
// One pitch-black screen, white type, hairline dividers, no cards, no page
// navigation: all sections are collapsible groups on a single list that opens
// fully collapsed, and a compact header with the search field stays pinned. Color rows open an HSV picker
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
    // Learned-word migration picker (SAF); the result flows into runWordImport().
    private lateinit var wordImportLauncher: ActivityResultLauncher<Array<String>>
    // Background photo picker ("Colors & background"). The result
    // is downscaled, recompressed and written atomically by importBackground().
    private lateinit var bgImageLauncher: ActivityResultLauncher<String>

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
        wordImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runWordImport(uri)
        }
        bgImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { importBackground(uri) }
                if (ok) {
                    prefs.edit().putLong(PrefsApplier.BG_VERSION_PREF, System.currentTimeMillis()).apply()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.toast_bg_pick_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        setContentView(R.layout.activity_settings_noir)

        list = findViewById(R.id.noir_list)
        searchInput = findViewById(R.id.noir_search_input)

        findViewById<TextView>(R.id.noir_header_version).apply {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (t: Exception) { ShadeLog.w("SettingsActivity", "version lookup failed", t, once = true); null }
            if (!version.isNullOrBlank()) {
                text = "v$version"
                visibility = View.VISIBLE
            }
        }

        adapter = SettingsAdapter(this, prefs)
        savedInstanceState?.getIntArray(STATE_EXPANDED)?.let { adapter.setExpanded(it) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // A header "change" animation cross-fades two copies of the row, which
        // reads as a flicker when the chevron flips.
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // The IME's search key used to do nothing, leaving the keyboard up.
        searchInput.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            WindowCompat.getInsetsController(window, v).hide(WindowInsetsCompat.Type.ime())
            true
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Rotation recreates the activity; keep the sections the person opened.
        outState.putIntArray(STATE_EXPANDED, adapter.expandedIds())
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Brings an expanded section's header to the top so its rows are in view. */
    internal fun scrollRowToTop(position: Int) {
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
    }

    private fun passphraseField(hintRes: Int) = EditText(this).apply {
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
    }

    /** AlertDialog.setView() puts the view edge to edge; give inputs the dialog's own gutters. */
    private fun dialogBody(vararg fields: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f).toInt(), dp(8f).toInt(), dp(20f).toInt(), 0)
        fields.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    // ── Backup / restore / update actions ─────────────────────────────────

    /** Called from the catalog's action items; ctx there is this activity. */
    internal fun startExport() = exportLauncher.launch("shade-backup.shdbak")

    internal fun startImport() = importLauncher.launch(arrayOf("*/*"))

    /**
     * Learned-word migration: reads a file exported from another keyboard
     * (backup archive, dictionary JSON or plain word list) and adds every
     * word found to the learned-word memory. Runs on IO, reports a summary.
     */
    internal fun startWordImport() = wordImportLauncher.launch(arrayOf("*/*"))

    private fun runWordImport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var report: DictionaryImport.Report? = null
            var failed = false
            try {
                report = DictionaryImport.importFromUri(applicationContext, uri)
            } catch (t: Exception) {
                ShadeLog.w("SettingsActivity", "word import failed", t, once = true)
                failed = true
            }
            withContext(Dispatchers.Main) {
                when {
                    failed -> Toast.makeText(this@SettingsActivity, R.string.import_words_failed, Toast.LENGTH_SHORT).show()
                    report == null || report.isEmpty ->
                        Toast.makeText(this@SettingsActivity, R.string.import_words_none, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.import_words_done, report.imported, report.duplicates),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    internal fun openTypingStats() {
        startActivity(Intent(this, TypingStatsActivity::class.java))
    }

    /** Asks for the backup passphrase, then runs Backup.export / Backup.import on IO. */
    private fun askBackupPassphrase(isExport: Boolean, uri: Uri) {
        val input = passphraseField(R.string.backup_pass_hint)
        // A typo in the only passphrase makes the backup permanently unreadable,
        // so exports ask twice.
        val confirm = if (isExport) passphraseField(R.string.backup_pass_confirm_hint) else null
        val dialog = AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(if (isExport) R.string.backup_pass_title_export else R.string.backup_pass_title_import))
            .setMessage(getString(R.string.backup_pass_warning))
            .setView(if (confirm != null) dialogBody(input, confirm) else dialogBody(input))
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
                if (isExport && pass != confirm?.text?.toString()) {
                    Toast.makeText(this, R.string.backup_pass_mismatch, Toast.LENGTH_SHORT).show()
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

    /**
     * GIF API key editor — writes the same pref the GIF panel's setup
     * card uses ([GifConfig.KEY_PREF]). Saving an empty key clears the runtime
     * override so the build-time key applies again.
     */
    internal fun showGifApiKeyDialog(prefs: SharedPreferences) {
        val input = EditText(this).apply {
            hint = getString(R.string.gif_key_hint)
            // VISIBLE_PASSWORD keeps a pasted key verbatim: no autocorrect,
            // no emoji suggestions mangling the token.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(R.string.pref_gif_api_key))
            .setMessage(getString(R.string.pref_gif_api_key_sum))
            .setView(dialogBody(input))
            .setPositiveButton(R.string.gif_key_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                prefs.edit().putString(GifConfig.KEY_PREF, key).apply()
                Toast.makeText(
                    this,
                    if (key.isEmpty()) R.string.gif_key_cleared else R.string.gif_key_saved,
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Multi-select for the dictionary languages (`dictionaries` is a string
     * set, so a single-value Choice row cannot edit it). Ids not offered here
     * are preserved, and an empty selection falls back to English.
     */
    internal fun showLanguagesDialog(prefs: SharedPreferences) {
        val ids = arrayOf("en", "hi", "hinglish")
        val labels = arrayOf(
            getString(R.string.lang_en), getString(R.string.lang_hi), getString(R.string.lang_hinglish)
        )
        val current = prefs.getStringSet("dictionaries", setOf("en")) ?: setOf("en")
        val checked = BooleanArray(ids.size) { ids[it] in current }
        AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(R.string.action_languages))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = ids.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                picked.addAll(current - ids.toSet())
                if (picked.isEmpty()) picked.add("en")
                prefs.edit().putStringSet("dictionaries", picked).apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun confirmClearLearned() {
        AlertDialog.Builder(this, R.style.ShadeDialogTheme)
            .setTitle(getString(R.string.action_clear_learned))
            .setMessage(getString(R.string.action_clear_learned_sub))
            .setPositiveButton(R.string.clear_confirm_button) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // clearAll covers the adaptive stores AND the service's live
                        // WordDictionary + BigramModel (when the keyboard has started in
                        // this process); file-only deletes would be resurrected by their
                        // in-memory maps on the next debounced save.
                        LearningDataCleaner.clearAll(applicationContext)
                    } catch (e: Exception) { Log.w("SettingsActivity", "Failed to clear learned data", e) }
                    withContext(Dispatchers.Main) {
                        // Was R.string.backup_done ("Backup saved") — wrong message for a wipe.
                        Toast.makeText(this@SettingsActivity, R.string.toast_learned_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var updateCheck: Job? = null

    internal fun checkForUpdates() {
        if (updateCheck?.isActive == true) return
        updateCheck = lifecycleScope.launch {
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

    // ── Colors & background ─────────────────────────────

    /** Opens the gallery picker for the keyboard background photo. */
    internal fun pickBackgroundImage() {
        runCatching { bgImageLauncher.launch("image/*") }
            .onFailure {
                Log.w("SettingsActivity", "background picker unavailable", it)
                Toast.makeText(this, R.string.toast_bg_pick_failed, Toast.LENGTH_LONG).show()
            }
    }

    /** Deletes the background photo and clears the version stamp. */
    internal fun removeBackgroundImage() {
        if (prefs.getLong(PrefsApplier.BG_VERSION_PREF, 0L) == 0L) {
            Toast.makeText(this, R.string.toast_bg_removed_none, Toast.LENGTH_SHORT).show()
            return
        }
        File(filesDir, PrefsApplier.BG_IMAGE_FILE).delete()
        prefs.edit().putLong(PrefsApplier.BG_VERSION_PREF, 0L).apply()
        Toast.makeText(this, R.string.toast_bg_removed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Resets colors and background: manual colors, design preset, key
     * opacity, transparency, the whole photo frame AND the photo itself go
     * back to their defaults (the row's subtitle promises "image"; previously
     * the picture stayed behind).
     */
    internal fun resetColorsAndBackground(prefs: SharedPreferences) {
        val keys = listOf(
            "color_bg", "color_key", "color_keyfn", "color_text", "color_accent",
            "design", "key_opacity", "bg_transparency",
            "photo_fit", "photo_zoom", "photo_width", "photo_height", "photo_x", "photo_y",
            "bg_dim", "bg_blur"
        )
        File(filesDir, PrefsApplier.BG_IMAGE_FILE).delete()
        prefs.edit().also { ed -> keys.forEach { ed.remove(it) } }
            .putLong(PrefsApplier.BG_VERSION_PREF, 0L)
            .apply()
        Toast.makeText(this, R.string.toast_colors_reset, Toast.LENGTH_SHORT).show()
        adapter.rebuild()
    }

    /**
     * Copies the picked image into filesDir as a downscaled JPEG:
     * ImageDecoder with a 1600 px longest-edge cap on API 28+, double-step
     * BitmapFactory sampling below.
     * The write is atomic (rename-on-commit) so the keyboard never decodes a
     * half-written file.
     */
    private fun importBackground(uri: Uri): Boolean = runCatching {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > 1600) decoder.setTargetSize(
                    (info.size.width * (1600f / edge)).toInt().coerceAtLeast(1),
                    (info.size.height * (1600f / edge)).toInt().coerceAtLeast(1)
                )
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxSide <= 0) return false // non-local return: runCatching is inline
            var sample = 1
            while (maxSide / (sample * 2) >= 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return false

        val file = AtomicFile(File(filesDir, PrefsApplier.BG_IMAGE_FILE))
        try {
            val stream = file.startWrite()
            try {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } finally {
            bmp.recycle()
        }
        true
    }.getOrDefault(false)

    // ── Color picker sheet ─────────────────────────────────────────

    /**
     * Bottom-anchored HSV picker for the catalog's color rows. Shows the
     * current color in a preview bar, a saturation/brightness square, a hue
     * rail, a preset grid (the row's auto color first so "back to default" is
     * one tap) and a hex field, all kept in sync. Saving stores a normalized
     * "#RRGGBB" under [key] — the same keys Themes.overrides() reads. The
     * override layers on top of the active theme; "Reset colors" clears all
     * overrides.
     */
    // Dialog content is detached from any root; null root is the dialog idiom.
    @SuppressLint("InflateParams")
    internal fun showColorPicker(key: String, titleRes: Int, autoColor: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<View>(R.id.picker_preview)
        val previewHex = view.findViewById<TextView>(R.id.picker_preview_hex)
        val picker = view.findViewById<ColorPickerView>(R.id.color_picker_view)
        val hexInput = view.findViewById<EditText>(R.id.picker_hex)
        val presets = view.findViewById<LinearLayout>(R.id.picker_presets)
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

        // One row of presets: the auto color first (so "back to default" is a
        // single tap), then black → white. Anything else is one drag away on the
        // picker itself, so the sheet carries no color swatches of its own.
        val palette = linkedSetOf(
            autoColor,
            0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(),
            0xFF48484A.toInt(), 0xFF8E8E93.toInt(), 0xFFC7C7CC.toInt(),
            0xFFFFFFFF.toInt()
        )
        val dotPx = dp(28f).toInt()
        val outline = getColor(R.color.noir_outline)
        for (c in palette) {
            val cell = FrameLayout(this)
            cell.contentDescription = ColorMath.formatHex(c)
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(dp(1f).toInt(), outline)
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
            // Weighted cells: eight fixed 40dp cells (320dp) overflowed phones narrower than 360dp.
            presets.addView(cell, LinearLayout.LayoutParams(0, dp(40f).toInt(), 1f))
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            // Without this the keyboard covers the hex field this sheet exists for.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
            // The override applies on top of the ACTIVE
            // theme (no forced switch to the legacy "custom" theme). Unset
            // slots keep the base; "Reset colors" clears everything.
            prefs.edit().putString(key, norm).apply()
            adapter.rebuild()
            dialog.dismiss()
        }
        dialog.show()
    }

    private companion object {
        const val STATE_EXPANDED = "expanded_sections"
    }
}

// ============================================================================
// Adapter — one flat RecyclerView of collapsible section headers and child
// rows. Every section starts collapsed, so the screen opens as a short index
// instead of a 100-row wall; search swaps the whole list for matching items.
// ============================================================================

internal class SettingsAdapter(
    private val ctx: SettingsActivity,
    private val prefs: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class Section(val def: SettingsCatalog.Section) : Row()
        class Child(val def: SettingsCatalog.Section, val item: SettingsItem, val isLast: Boolean) : Row()
        class Empty(val textRes: Int) : Row()
    }

    private val rows = ArrayList<Row>()
    private val collapsed = HashSet<Int>(SettingsCatalog.sections.map { it.id })
    private var searching = false

    internal val TYPE_SECTION = 0
    internal val TYPE_TOGGLE = 1
    internal val TYPE_SLIDER = 2
    internal val TYPE_ACTION = 3
    internal val TYPE_CHOICE = 4
    internal val TYPE_COLOR = 5
    internal val TYPE_INFO = 6
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
            appendCatalog()
        } else {
            searching = true
            val results = SettingsCatalog.search(q) { ctx.getString(it) }
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
        ctx.scrollRowToTop(headerPos)
    }

    fun toggleSection(sectionId: Int) {
        if (sectionId in collapsed) expand(sectionId) else collapse(sectionId)
    }

    fun isSectionCollapsed(sectionId: Int): Boolean = sectionId in collapsed

    /** Ids of the sections currently open, for saving across rotation. */
    fun expandedIds(): IntArray =
        SettingsCatalog.sections.map { it.id }.filter { it !in collapsed }.toIntArray()

    /** Restores [expandedIds]; every other section is collapsed. */
    fun setExpanded(ids: IntArray) {
        collapsed.clear()
        collapsed.addAll(SettingsCatalog.sections.map { it.id })
        ids.forEach { collapsed.remove(it) }
        rebuild()
    }

    /** The item rendered at [position], or null for structural rows. */
    internal fun itemAt(position: Int): SettingsItem? =
        (rows.getOrNull(position) as? Row.Child)?.item

    fun sectionAt(position: Int): SettingsCatalog.Section? =
        (rows.getOrNull(position) as? Row.Section)?.def

    // ── RecyclerView plumbing ────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int = when (val r = rows[position]) {
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

    internal class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(def: SettingsCatalog.Section, isCollapsed: Boolean, onToggle: (Int) -> Unit) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_section_title).text = c.getString(def.titleRes)
            itemView.findViewById<View>(R.id.noir_section_arrow).rotation = if (isCollapsed) 0f else 90f
            // TalkBack otherwise reads a header with no hint that it opens.
            ViewCompat.setStateDescription(
                itemView,
                c.getString(if (isCollapsed) R.string.state_collapsed else R.string.state_expanded)
            )
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
            itemView.bindDivider(isLast)
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

        fun bind(item: SettingsItem.Slider, prefs: SharedPreferences, isLast: Boolean) {
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
                    // Live preview: each slider tick plays the effect
                    // immediately.
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

        private companion object {
            /** Up to this many options fit side by side as a segmented control. */
            const val SEGMENT_MAX = 3
        }

        fun bind(item: SettingsItem.Choice, prefs: SharedPreferences, isLast: Boolean) {
            val c = itemView.context
            itemView.findViewById<TextView>(R.id.noir_title).text = c.getString(item.titleRes)
            val group = itemView.findViewById<MaterialButtonToggleGroup>(R.id.noir_group)
            val valueTv = itemView.findViewById<TextView>(R.id.noir_choice_value)
            val chevron = itemView.findViewById<View>(R.id.noir_choice_chevron)

            // A stored value that is no longer an option (renamed/legacy pref)
            // used to leave NOTHING selected; fall back to the default.
            val current = prefs.getString(item.key, item.default)
                ?.takeIf { v -> item.options.any { it.first == v } } ?: item.default

            group.clearOnButtonCheckedListeners()
            group.removeAllViews()

            if (item.options.size > SEGMENT_MAX) {
                // Six theme names or seven design names cannot share one phone-wide
                // row without wrapping mid-word: show the value, pick from a list.
                group.visibility = View.GONE
                valueTv.visibility = View.VISIBLE
                chevron.visibility = View.VISIBLE
                valueTv.text = labelOf(item, current)
                itemView.isClickable = true
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
            } else {
                valueTv.visibility = View.GONE
                chevron.visibility = View.GONE
                group.visibility = View.VISIBLE
                itemView.setOnClickListener(null)
                itemView.isClickable = false
                bindSegments(item, prefs, group, current)
            }
            itemView.bindDivider(isLast)
        }

        private fun labelOf(item: SettingsItem.Choice, value: String): String {
            val res = item.options.firstOrNull { it.first == value }?.second ?: item.options.first().second
            return itemView.context.getString(res)
        }

        private fun bindSegments(
            item: SettingsItem.Choice, prefs: SharedPreferences,
            group: MaterialButtonToggleGroup, current: String
        ) {
            val c = itemView.context
            // singleSelection is FALSE by default in Material 1.12: without this, tapping a new
            // option leaves the old one checked, and tapping the checked one deselects it.
            group.isSingleSelection = true
            group.isSelectionRequired = true

            val white = c.getColor(R.color.noir_text)
            val black = c.getColor(R.color.noir_on_button)
            val checkedState = intArrayOf(android.R.attr.state_checked)
            item.options.forEach { (value, labelRes) ->
                val btn = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = c.getString(labelRes)
                    id = View.generateViewId()
                    isAllCaps = false
                    maxLines = 1
                    textSize = 14f
                    stateListAnimator = null
                    insetTop = 0
                    insetBottom = 0
                    strokeWidth = c.dp(1f).toInt()
                    strokeColor = ColorStateList.valueOf(c.getColor(R.color.noir_outline))
                    rippleColor = ColorStateList.valueOf(c.getColor(R.color.noir_ripple))
                    backgroundTintList = ColorStateList(
                        arrayOf(checkedState, intArrayOf()),
                        intArrayOf(white, Color.TRANSPARENT)
                    )
                    setTextColor(ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(black, white)))
                    minimumHeight = c.dp(44f).toInt()
                    if (value == current) isChecked = true
                }
                group.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            group.addOnButtonCheckedListener { g, checkedId, isChecked ->
                if (isChecked) {
                    val idx = g.indexOfChild(g.findViewById(checkedId))
                    if (idx in item.options.indices) {
                        prefs.edit().putString(item.key, item.options[idx].first).apply()
                    }
                }
            }
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
                setStroke(c.dp(1f).toInt(), c.getColor(R.color.noir_outline))
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
