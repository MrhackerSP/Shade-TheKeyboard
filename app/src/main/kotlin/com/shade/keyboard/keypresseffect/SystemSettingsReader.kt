package com.shade.keyboard.keypresseffect

import android.content.ContentResolver
import android.provider.Settings

/**
 * Reader for the system settings the engine gates on. All of them live in the
 * `Settings.System` table:
 *
 *  - `vibrate_on`                    (bool, default 1)  — master system switch
 *  - `keyboard_vibration_enabled`    (bool, default 1)  — < API 37 gate
 *  - `keyboard_vibration_intensity`  (long, default -1) — API 37+ gate
 *  - `haptic_feedback_enabled`       (bool, default 1)  — cached below API 33
 *
 * Every read is failure-tolerant: the `with-default` overloads of
 * `Settings.System` already return the default when the key is absent, and the
 * runCatching below covers exotic providers that throw.
 */
internal object SystemSettingsReader {
    const val SETTING_VIBRATE_ON: String = "vibrate_on"
    const val SETTING_KEYBOARD_VIBRATION_ENABLED: String = "keyboard_vibration_enabled"
    const val SETTING_KEYBOARD_VIBRATION_INTENSITY: String = "keyboard_vibration_intensity"
    const val SETTING_HAPTIC_FEEDBACK_ENABLED: String = "haptic_feedback_enabled"

    fun systemBool(cr: ContentResolver, key: String, def: Boolean): Boolean =
        runCatching { Settings.System.getInt(cr, key, if (def) 1 else 0) }
            .getOrDefault(if (def) 1 else 0) != 0

    fun systemLong(cr: ContentResolver, key: String, def: Long): Long =
        runCatching { Settings.System.getLong(cr, key, def) }.getOrDefault(def)
}
