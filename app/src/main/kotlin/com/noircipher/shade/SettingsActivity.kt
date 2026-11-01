package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private var currentScreenTitleRes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.apply {
            elevation = 0f
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(false)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            updateActionBar()
        }

        // The main menu is just a set of clickable rows that open
        // specific preference screens inside a new fragment.
        findViewById<View>(R.id.row_languages).setOnClickListener { openScreen("screen_layouts", R.string.pref_languages) }
        findViewById<View>(R.id.row_preferences).setOnClickListener { openScreen("screen_feedback", R.string.pref_preferences) }
        findViewById<View>(R.id.row_theme).setOnClickListener { openScreen("screen_appearance", R.string.pref_theme) }
        findViewById<View>(R.id.row_colors).setOnClickListener { openScreen("screen_colors", R.string.scr_colors) }
        findViewById<View>(R.id.row_corrections).setOnClickListener { openScreen("screen_typing", R.string.pref_corrections) }
        findViewById<View>(R.id.row_learning).setOnClickListener { openScreen("screen_learning", R.string.scr_learning) }
        findViewById<View>(R.id.row_voice).setOnClickListener { toast(R.string.settings_coming_soon) }
        findViewById<View>(R.id.row_emoji).setOnClickListener { toast(R.string.settings_coming_soon) }
        findViewById<View>(R.id.row_clipboard).setOnClickListener { openScreen("screen_toolbar", R.string.pref_clipboard) }
        findViewById<View>(R.id.row_typing).setOnClickListener { startActivity(Intent(this, TypingStatsActivity::class.java)) }
        findViewById<View>(R.id.row_backup).setOnClickListener { openScreen("screen_backup", R.string.pref_backup) }
        findViewById<View>(R.id.row_about).setOnClickListener { openScreen("screen_about", R.string.pref_about) }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun updateActionBar() {
        val canNavigateBack = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.setDisplayHomeAsUpEnabled(canNavigateBack)
        supportActionBar?.setDisplayShowTitleEnabled(canNavigateBack)
        supportActionBar?.setTitle(if (canNavigateBack) currentScreenTitleRes else 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun openScreen(rootKey: String, titleRes: Int) {
        currentScreenTitleRes = titleRes
        updateActionBar()
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) saveBackground(uri)
        }
        private var pendingPassphrase: String? = null

        private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val pass = pendingPassphrase
            pendingPassphrase = null
            if (uri != null && pass != null) {
                toast(if (Backup.export(requireContext(), uri, pass)) R.string.backup_done else R.string.backup_failed)
            }
        }
        private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                if (Backup.isEncrypted(requireContext(), uri)) {
                    promptPassphrase(forExport = false) { pass -> finishImport(uri, pass) }
                } else {
                    toast(R.string.backup_not_supported)
                }
            }
        }
        private val exportTraining = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                val ok = TrainingExport.exportToUri(requireContext(), uri)
                toast(if (ok) R.string.export_training_done else R.string.export_training_failed)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            refreshLanguagePref()

            click("clear_learned") {
                confirm(R.string.pref_clear_confirm) {
                    WordDictionary.clearLearned(ctx)
                    LearningDataCleaner.clearAll(ctx)
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_adaptive") {
                confirm(R.string.pref_clear_confirm) {
                    LearningDataCleaner.clearOne(ctx, "shade_adaptive_words")
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_appctx") {
                confirm(R.string.pref_clear_confirm) {
                    LearningDataCleaner.clearOne(ctx, "shade_app_context")
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_suppressor") {
                confirm(R.string.pref_clear_confirm) {
                    LearningDataCleaner.clearOne(ctx, "shade_autocorrect_suppressor")
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_calibrator") {
                confirm(R.string.pref_clear_confirm) {
                    LearningDataCleaner.clearOne(ctx, "shade_confidence_calibrator")
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_ranker") {
                confirm(R.string.pref_clear_confirm) {
                    LearningDataCleaner.clearOne(ctx, "shade_online_ranker")
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_learning_all") {
                confirm(R.string.pref_learning_clear_all_confirm) {
                    LearningDataCleaner.clearAll(ctx)
                    toast(R.string.pref_learning_cleared)
                }
            }
            click("clear_clipboard") {
                ClipboardStore(ctx).clearAll()
                toast(R.string.done_toast)
            }
            click("bg_pick") { pickBg.launch(arrayOf("image/*")) }
            click("bg_clear") {
                File(ctx.filesDir, "bg.jpg").delete()
                toast(R.string.done_toast)
            }
            click("backup_export") {
                promptPassphrase(forExport = true) { pass ->
                    pendingPassphrase = pass
                    exportBackup.launch("shade-backup.shdbak")
                }
            }
            click("backup_import") { importBackup.launch(arrayOf("*/*")) }

            click("export_training") {
                if (!TrainingExport.hasTrainingData(ctx)) {
                    toast(R.string.export_training_none)
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.export_training_confirm)
                        .setMessage(R.string.export_training_confirm_msg)
                        .setPositiveButton(R.string.yes) { _, _ -> exportTraining.launch("shade-training.zip") }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            }

            click("clear_training_export") {
                confirm(R.string.clear_training_confirm) {
                    TrainingExport.clearAutoExports(ctx)
                    toast(R.string.clear_training_done)
                }
            }

            findPreference<SwitchPreferenceCompat>("contribute_data")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val on = newValue as? Boolean ?: true
                    if (!on) TrainingExport.clearAutoExports(ctx)
                    true
                }

            findPreference<EditTextPreference>("custom_layout")
                ?.setOnBindEditTextListener {
                    it.setSingleLine(false)
                    it.minLines = 5
                    it.setHorizontallyScrolling(false)
                    it.hint = "q w e r t y u i o p\na|àá s d f g h j k l\nz x c v b n m"
                }
            findPreference<EditTextPreference>("custom_layout")
                ?.setOnPreferenceChangeListener { _, v ->
                    if (Layouts.parseCustom(v?.toString() ?: "") == null) toast(R.string.layout_invalid)
                    true
                }
            for (k in listOf("color_bg", "color_key", "color_keyfn", "color_text", "color_accent")) {
                findPreference<EditTextPreference>(k)?.setOnBindEditTextListener { it.hint = "#RRGGBB" }
            }

            // Resize keyboard: sets a flag that the IME reads on next input
            // start. The IME shows the drag handle, the user resizes, and the
            // handle auto-hides. We finish the settings activity so the user
            // sees the keyboard with the handle.
            click("resize_keyboard") {
                PreferenceManager.getDefaultSharedPreferences(ctx)
                    .edit().putBoolean("resize_mode_requested", true).apply()
                requireActivity().finish()
            }
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                action()
                true
            }
        }

        private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

        private fun finishImport(uri: Uri, passphrase: String) {
            val ok = Backup.import(requireContext(), uri, passphrase)
            toast(if (ok) R.string.restore_done else R.string.backup_wrong_passphrase)
        }

        /** Asks for a backup passphrase; export asks twice and enforces a minimum length. */
        private fun promptPassphrase(forExport: Boolean, onDone: (String) -> Unit) {
            val c = requireContext()
            val pad = (20 * c.resources.displayMetrics.density).toInt()
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            fun field(hintRes: Int): EditText = EditText(c).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setHint(hintRes)
            }
            val pass = field(R.string.backup_pass_hint)
            box.addView(pass)
            val confirm: EditText? = if (forExport) field(R.string.backup_pass_confirm_hint).also { box.addView(it) } else null

            val builder = AlertDialog.Builder(c)
                .setTitle(if (forExport) R.string.backup_pass_title_export else R.string.backup_pass_title_import)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
            if (forExport) builder.setMessage(R.string.backup_pass_warning)
            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val p = pass.text.toString()
                    when {
                        p.isEmpty() || (forExport && p.length < 8) -> pass.error = getString(R.string.backup_pass_short)
                        confirm != null && p != confirm.text.toString() -> confirm.error = getString(R.string.backup_pass_mismatch)
                        else -> {
                            dialog.dismiss()
                            onDone(p)
                        }
                    }
                }
            }
            dialog.show()
        }

        private fun confirm(msg: Int, ok: () -> Unit) {
            AlertDialog.Builder(requireContext())
                .setMessage(msg)
                .setPositiveButton(R.string.yes) { _, _ -> ok() }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        private fun refreshLanguagePref() {
            val pref = findPreference<MultiSelectListPreference>("active_languages") ?: return
            val fromLanguageDirs = try {
                requireContext().assets.list("languages")
                    ?.filter { dir -> !dir.contains('.') }
                    ?.filter { dir ->
                        requireContext().assets.list("languages/$dir")?.contains("dictionary.txt") == true
                    }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val fromLegacy = try {
                requireContext().assets.list("wordlists")?.filter { it.endsWith(".txt") }?.map { it.removeSuffix(".txt") } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val available = (fromLanguageDirs + fromLegacy).distinct().sorted()

            if (available.isNotEmpty()) {
                pref.entries = available.map { localeName(it) }.toTypedArray()
                pref.entryValues = available.toTypedArray()
            }
        }

        private fun localeName(code: String): String = when (code) {
            "en" -> "English"; "hi" -> "हिन्दी (Hindi)"; "hinglish" -> "Hinglish"
            "it" -> "Italiano"; "pt" -> "Português"; "ru" -> "Русский"; else -> code.uppercase()
        }

        private fun saveBackground(uri: Uri) {
            val ctx = requireContext()
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(File(ctx.filesDir, "bg.jpg")).use { input.copyTo(it) }
                }
                toast(R.string.done_toast)
            } catch (_: Exception) {
                toast(R.string.backup_failed)
            }
        }
    }
}
