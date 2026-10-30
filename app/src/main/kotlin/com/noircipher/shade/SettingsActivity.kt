package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
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
        findViewById<View>(R.id.row_preferences).setOnClickListener { openScreen("screen_toolbar", R.string.pref_preferences) }
        findViewById<View>(R.id.row_theme).setOnClickListener { openScreen("screen_appearance", R.string.pref_theme) }
        findViewById<View>(R.id.row_corrections).setOnClickListener { openScreen("screen_typing", R.string.pref_corrections) }
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
        private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                toast(if (Backup.export(requireContext(), uri)) R.string.backup_done else R.string.backup_failed)
            }
        }
        private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast(if (Backup.import(requireContext(), uri)) R.string.restore_done else R.string.backup_failed)
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

            click("clear_learned") { confirm(R.string.pref_clear_confirm) { WordDictionary.clearLearned(ctx) } }
            click("clear_clipboard") {
                ClipboardStore(ctx).clearAll()
                toast(R.string.done_toast)
            }
            click("bg_pick") { pickBg.launch(arrayOf("image/*")) }
            click("bg_clear") {
                File(ctx.filesDir, "bg.jpg").delete()
                toast(R.string.done_toast)
            }
            click("backup_export") { exportBackup.launch("shade-backup.json") }
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
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                action()
                true
            }
        }

        private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

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
