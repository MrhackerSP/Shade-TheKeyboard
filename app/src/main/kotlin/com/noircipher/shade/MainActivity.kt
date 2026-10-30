package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var statusEnable: TextView? = null
    private var statusSelect: TextView? = null
    private var btnEnable: Button? = null
    private var btnSelect: Button? = null
    private var btnSettings: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = getColor(R.color.settings_background)
        window.navigationBarColor = getColor(R.color.settings_background)

        statusEnable = findViewById(R.id.status_enable)
        statusSelect = findViewById(R.id.status_select)
        btnEnable = findViewById(R.id.btn_enable)
        btnSelect = findViewById(R.id.btn_select)
        btnSettings = findViewById(R.id.btn_settings)

        btnEnable?.setOnClickListener {
            startActivitySafe(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        btnSelect?.setOnClickListener {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            } catch (_: Exception) {
                startActivitySafe(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        btnSettings?.setOnClickListener {
            startActivitySafe(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val selected = current?.startsWith("$packageName/") == true
            setStatus(statusEnable, enabled)
            setStatus(statusSelect, selected)
        } catch (_: Exception) {
            setStatus(statusEnable, false)
            setStatus(statusSelect, false)
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
