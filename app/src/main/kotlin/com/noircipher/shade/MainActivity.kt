package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var statusEnable: TextView? = null
    private var statusSelect: TextView? = null
    private var btnEnable: Button? = null
    private var btnSelect: Button? = null
    private var btnTry: Button? = null
    private var btnSettings: Button? = null
    private var testField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = getColor(R.color.settings_background)
        window.navigationBarColor = getColor(R.color.settings_background)

        statusEnable = findViewById(R.id.status_enable)
        statusSelect = findViewById(R.id.status_select)
        btnEnable = findViewById(R.id.btn_enable)
        btnSelect = findViewById(R.id.btn_select)
        btnTry = findViewById(R.id.btn_try)
        btnSettings = findViewById(R.id.btn_settings)
        testField = findViewById(R.id.test_field)

        btnEnable?.setOnClickListener {
            startActivitySafe(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        btnSelect?.setOnClickListener { openDefaultKeyboardPicker(it) }
        btnTry?.setOnClickListener { onTryKeyboard(it) }
        btnSettings?.setOnClickListener {
            startActivitySafe(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /**
     * Deep-link to the system "Default keyboard" picker. Bound both from
     * [R.id.btn_select] (which is re-labelled "Set as default" when Shade is
     * enabled but not yet the default IME) and any other view that wants to
     * trigger the same flow via `android:onClick`.
     */
    fun openDefaultKeyboardPicker(view: View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } catch (_: Exception) {
            startActivitySafe(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    /**
     * "Try the keyboard" CTA. Focuses the test field so the soft keyboard
     * appears. If Shade is not the current default IME, we additionally open
     * the system picker so the user can switch to Shade before typing.
     */
    private fun onTryKeyboard(view: View) {
        val tf = testField
        if (tf != null) {
            tf.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(tf, InputMethodManager.SHOW_IMPLICIT)
        }
        if (!isShadeDefaultIme()) {
            openDefaultKeyboardPicker(view)
        }
    }

    private fun refreshStatus() {
        var enabled = false
        var selected = false
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            selected = current?.startsWith("$packageName/") == true
        } catch (_: Exception) {
            enabled = false
            selected = false
        }
        setStatus(statusEnable, enabled)
        setStatus(statusSelect, selected)

        // When Shade is enabled but not the default IME, surface a more
        // prominent "Set as default" CTA on the step-2 button. Otherwise fall
        // back to the neutral "Choose Shade" label.
        val urgent = enabled && !selected
        btnSelect?.apply {
            text = getString(if (urgent) R.string.set_as_default else R.string.choose_keyboard)
            setBackgroundResource(
                if (urgent) R.drawable.bg_app_button_urgent else R.drawable.bg_app_button
            )
        }
    }

    private fun isShadeDefaultIme(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            current?.startsWith("$packageName/") == true
        } catch (_: Exception) {
            false
        }
    }

    private fun setStatus(view: TextView?, done: Boolean) {
        view ?: return
        view.setText(if (done) R.string.status_done else R.string.status_todo)
        view.setBackgroundResource(if (done) R.drawable.bg_status_done else R.drawable.bg_status_todo)
        view.contentDescription = getString(if (done) R.string.status_done else R.string.status_todo)
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Keep the setup screen alive if a system screen is unavailable.
        }
    }
}
