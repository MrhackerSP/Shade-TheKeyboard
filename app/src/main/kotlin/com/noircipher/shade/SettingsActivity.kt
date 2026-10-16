package com.noircipher.shade

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen
    ): Boolean {
        val f = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, f)
            .addToBackStack(null)
            .commit()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val pickBg = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) saveBackground(uri)
        }
        private val exportBackup = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                toast(
                    if (Backup.export(requireContext(), uri)) R.string.backup_done
                    else R.string.backup_failed
                )
            }
        }
        private val importBackup = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                toast(
                    if (Backup.import(requireContext(), uri)) R.string.restore_done
                    else R.string.backup_failed
                )
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            refreshLanguagePref()

            click("clear_learned") {
                confirm(R.string.pref_clear_confirm) {
                    WordDictionary.clearLearned(ctx)
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
            click("backup_export") { exportBackup.launch("shade-backup.json") }
            click("backup_import") { importBackup.launch(arrayOf("*/*")) }

            findPreference<EditTextPreference>("custom_layout")
                ?.setOnBindEditTextListener {
                    it.setSingleLine(false)
                    it.minLines = 5
                    it.setHorizontallyScrolling(false)
                    it.hint = "q w e r t y u i o p\na|àá s d f g h j k l\nz x c v b n m"
                }
            findPreference<EditTextPreference>("custom_layout")
                ?.setOnPreferenceChangeListener { _, v ->
                    if (Layouts.parseCustom(v?.toString() ?: "") == null) {
                        toast(R.string.layout_invalid)
                    }
                    true
                }
            for (k in listOf("color_bg", "color_key", "color_keyfn", "color_text", "color_accent")) {
                findPreference<EditTextPreference>(k)
                    ?.setOnBindEditTextListener { it.hint = "#RRGGBB" }
            }
        }

        override fun onResume() {
            super.onResume()
            activity?.title = preferenceScreen?.title?.toString()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.settings_title)
        }

        private fun click(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                action()
                true
            }
        }

        private fun toast(res: Int) =
            Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

        private fun confirm(msg: Int, ok: () -> Unit) {
            AlertDialog.Builder(requireContext())
                .setMessage(msg)
                .setPositiveButton(R.string.yes) { _, _ -> ok() }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        /**
         * Populate the language multi-select from bundled assets.
         * Only locales present in assets/wordlists/ are listed.
         */
        private fun refreshLanguagePref() {
            val pref = findPreference<MultiSelectListPreference>("active_languages") ?: return
            val ctx = requireContext()
            val available = try {
                ctx.assets.list("wordlists")
                    ?.filter { it.endsWith(".txt") }
                    ?.map { it.removeSuffix(".txt") }
                    ?.sorted()
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (available.isEmpty()) return

            val names = available.map { localeName(it) }
            pref.entries = names.toTypedArray()
            pref.entryValues = available.toTypedArray()
        }

        private fun localeName(code: String): String = when (code) {
            "en" -> "English"
            "de" -> "Deutsch"
            "fr" -> "Français"
            "es" -> "Español"
            "it" -> "Italiano"
            "pt" -> "Português"
            "ru" -> "Русский"
            else -> code.uppercase()
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
