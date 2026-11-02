package com.noircipher.shade

import android.content.SharedPreferences

/**
 * Keeps SharedPreferences value types consistent between the settings screen and the keyboard.
 *
 * `keyboard_offset_y` is shown as a SeekBarPreference (always an Int), but older builds of the
 * keyboard stored it with putFloat(). Reading a Float as an Int (or vice versa) throws
 * ClassCastException, which crashed the settings list or the keyboard itself.
 */
object PrefsMigration {

    const val KEY_OFFSET_Y = "keyboard_offset_y"

    /** Converts a legacy Float value to the Int the settings slider expects. Safe to call repeatedly. */
    fun run(prefs: SharedPreferences) {
        try {
            val v = prefs.all[KEY_OFFSET_Y] ?: return
            if (v !is Int) {
                prefs.edit().putInt(KEY_OFFSET_Y, (v as? Number)?.toInt() ?: 0).apply()
            }
        } catch (_: Exception) {
            try { prefs.edit().remove(KEY_OFFSET_Y).apply() } catch (_: Exception) {}
        }
    }

    /** Reads the floating-keyboard offset whatever type an older build stored it as. */
    fun readOffsetY(prefs: SharedPreferences): Float = try {
        prefs.getInt(KEY_OFFSET_Y, 0).toFloat()
    } catch (_: ClassCastException) {
        try { prefs.getFloat(KEY_OFFSET_Y, 0f) } catch (_: ClassCastException) { 0f }
    }

    fun writeOffsetY(prefs: SharedPreferences, value: Float) {
        prefs.edit().putInt(KEY_OFFSET_Y, Math.round(value)).apply()
    }
}
