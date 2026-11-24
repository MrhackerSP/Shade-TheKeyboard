package com.shade.keyboard

import android.content.SharedPreferences
import android.util.Log

/**
 * Keeps SharedPreferences value types consistent between the settings screen and the keyboard.
 *
 * `keyboard_offset_y` is shown as a SeekBarPreference (always an Int), but older builds of the
 * keyboard stored it with putFloat(). Reading a Float as an Int (or vice versa) throws
 * ClassCastException, which crashed the settings list or the keyboard itself.
 */
object PrefsMigration {

    const val KEY_OFFSET_Y = "keyboard_offset_y"

    /**
     * One-time flag guarding the retired-vibration-keys cleanup below — run()
     * executes on every service start, and a user-picked strength must survive.
     */
    private const val KEY_VIBRATION_MIGRATION = "vibration_default_migration"

    /** Converts a legacy Float value to the Int the settings slider expects. Safe to call repeatedly. */
    fun run(prefs: SharedPreferences) {
        try {
            // Retired vibration prefs: the old auto-seeded "vibration_duration"
            // (from the dead "haptic_strength" selector) would silently force
            // the custom-strength effect on every keypress. Gboard's slider
            // default state is "System default" — the OS keyboard tap — so
            // start everyone there and let the new slider set custom values.
            if (!prefs.getBoolean(KEY_VIBRATION_MIGRATION, false)) {
                prefs.edit()
                    .remove("vibration_duration")
                    .remove("haptic_strength")
                    .putBoolean(KEY_VIBRATION_MIGRATION, true)
                    .apply()
            }
            val v = prefs.all[KEY_OFFSET_Y] ?: return
            if (v !is Int) {
                prefs.edit().putInt(KEY_OFFSET_Y, (v as? Number)?.toInt() ?: 0).apply()
            }
        } catch (e: Exception) {
            ShadeLog.w("PrefsMigration", "prefs migration (offset) failed", e, once = true)
            try { prefs.edit().remove(KEY_OFFSET_Y).apply() } catch (e: Exception) { Log.w("PrefsMigration", "Prefs migration (offset cleanup) failed", e) }
        }
    }

    /** Reads the floating-keyboard offset whatever type an older build stored it as. */
    fun readOffsetY(prefs: SharedPreferences): Float = try {
        prefs.getInt(KEY_OFFSET_Y, 0).toFloat()
    } catch (e: ClassCastException) {
        ShadeLog.w("PrefsMigration", "legacy offset stored as non-Int", e, once = true)
        try { prefs.getFloat(KEY_OFFSET_Y, 0f) } catch (t: ClassCastException) { ShadeLog.w("PrefsMigration", "legacy offset unreadable; using 0", t, once = true); 0f }
    }

    fun writeOffsetY(prefs: SharedPreferences, value: Float) {
        prefs.edit().putInt(KEY_OFFSET_Y, Math.round(value)).apply()
    }
}
