package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusEnable: TextView
    private lateinit var statusSelect: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusEnable = findViewById(R.id.status_enable)
        statusSelect = findViewById(R.id.status_select)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith("$packageName/") == true
        statusEnable.setText(if (enabled) R.string.status_done else R.string.status_todo)
        statusSelect.setText(if (selected) R.string.status_done else R.string.status_todo)
    }
}
