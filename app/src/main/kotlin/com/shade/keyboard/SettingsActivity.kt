package com.shade.keyboard

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.lifecycle.lifecycleScope
import com.shade.keyboard.gif.GifConfig
import com.shade.keyboard.migration.DictionaryImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
// Shade settings — Gboard-style.
//
// A pitch-black "Noir" canvas like Gboard's dark settings: the HOME lists the
// categories as grouped rounded cards (Languages, Preferences, Theme /
// Corrections and suggestions, Glide typing, Voice typing, Emojis and GIFs /
// Clipboard, Dictionary / Privacy, About, Advanced settings, Shade updates);
// each category opens a PAGE of labelled sub-sections whose rows use Gboard's
// check / X switch. Search stays pinned on the home screen. Color rows open an
// HSV picker sheet built entirely in-project.
//
// Navigation is a single activity: the adapter swaps between home and page
// rows, a round back button / the system Back gesture returns to the home.
//
// All SharedPreferences keys and defaults match what ShadeService.applyPrefs()
// reads — see SettingsCatalog (pages + sections) and SettingsAdapter (rows).
// ============================================================================

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    internal lateinit var adapter: SettingsAdapter
    private lateinit var list: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var backButton: ImageButton
    private lateinit var headerTitle: TextView
    private lateinit var headerVersion: TextView
    private lateinit var backCallback: OnBackPressedCallback
    private var versionLabel: String? = null
    /** Home-list scroll position, restored when coming back from a page. */
    private var homeScroll: Parcelable? = null

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
        backButton = findViewById(R.id.noir_back)
        headerTitle = findViewById(R.id.noir_header_title)
        headerVersion = findViewById(R.id.noir_header_version)

        versionLabel = try {
            packageManager.getPackageInfo(packageName, 0).versionName
                ?.takeIf { it.isNotBlank() }?.let { "v$it" }
        } catch (t: Exception) { ShadeLog.w("SettingsActivity", "version lookup failed", t, once = true); null }
        headerVersion.text = versionLabel.orEmpty()

        adapter = SettingsAdapter(this, prefs)
        savedInstanceState?.getInt(STATE_PAGE, SettingsAdapter.HOME)?.let {
            if (it != SettingsAdapter.HOME) adapter.showPage(it)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // A header "change" animation cross-fades two copies of the row, which
        // reads as a flicker.
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

        // Back: page -> home. On the home the system default applies (leave settings).
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { goHome() }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        backButton.setOnClickListener { goHome() }

        // The large page title collapses into the top bar as it scrolls away.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { updateInlineTitle() }
        })

        adapter.rebuild()
        renderChrome()
    }

    override fun onResume() {
        super.onResume()
        // An import may have rewritten prefs behind our back.
        adapter.rebuild()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Rotation recreates the activity; keep the page the person is on.
        outState.putInt(STATE_PAGE, adapter.currentPageId)
    }

    // ── Navigation (home <-> page) ─────────────────────────────────────────

    /** Opens one settings category (called by a home-screen row). */
    internal fun openPage(pageId: Int) {
        if (adapter.currentPageId == pageId) return
        if (adapter.isHome) homeScroll = list.layoutManager?.onSaveInstanceState()
        adapter.showPage(pageId)
        list.scrollToPosition(0)
        renderChrome()
        animateSwap(forward = true)
    }

    /** Returns to the settings home, restoring its scroll position. */
    internal fun goHome() {
        if (adapter.isHome) return
        adapter.showHome()
        val state = homeScroll
        if (state != null) list.post { list.layoutManager?.onRestoreInstanceState(state) }
        else list.scrollToPosition(0)
        renderChrome()
        animateSwap(forward = false)
    }

    /** Top bar, search field and system-back state for the current screen. */
    private fun renderChrome() {
        val home = adapter.isHome
        val page = adapter.currentPage()
        backButton.visibility = if (home) View.GONE else View.VISIBLE
        searchInput.visibility = if (home) View.VISIBLE else View.GONE
        headerVersion.visibility = if (home && !versionLabel.isNullOrEmpty()) View.VISIBLE else View.GONE
        backCallback.isEnabled = !home
        headerTitle.text = getString(page?.titleRes ?: R.string.settings_title)
        (headerTitle.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            // Pages leave room for the 44dp round back button (16dp margins either side).
            lp.marginStart = dp(if (home) 28f else 76f).toInt()
            headerTitle.layoutParams = lp
        }
        updateInlineTitle()
    }

    /**
     * Home and small-title pages always show the top-bar title; large-title pages
     * fade it in once the big heading has scrolled out of view.
     */
    private fun updateInlineTitle() {
        val page = adapter.currentPage()
        if (page == null || !page.largeTitle) { headerTitle.alpha = 1f; return }
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        headerTitle.alpha = when {
            first < 0 -> 0f
            first > 0 -> 1f
            else -> {
                val v = lm.findViewByPosition(0)
                if (v == null || v.height <= 0) 1f
                else ((-v.top).toFloat() / (v.height * 0.6f)).coerceIn(0f, 1f)
            }
        }
    }

    private fun animateSwap(forward: Boolean) {
        list.animate().cancel()
        list.alpha = 0f
        list.translationX = dp(24f) * (if (forward) 1f else -1f)
        list.animate().alpha(1f).translationX(0f).setDuration(180L).start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

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
        const val STATE_PAGE = "settings_page"
    }
}
