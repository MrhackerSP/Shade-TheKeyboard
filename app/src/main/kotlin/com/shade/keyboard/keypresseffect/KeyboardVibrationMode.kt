package com.shade.keyboard.keypresseffect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Intensity-mode resolver — decides whether the keyboard drives key-press
 * vibration itself or defers to the system's keyboard-vibration setting.
 *
 * Resolution order: no keyboard-vibration settings UI on the device →
 * app-controlled; system master vibration off, keyboard vibration disabled
 * (below API 37), or keyboard-vibration intensity zero (API 37+) → silent;
 * otherwise the system owns the switch.
 *
 * On Android < 14 the keyboard-vibration settings screen does not exist and the
 * keyboard always drives vibration itself ([MODE_APP_CONTROLLED]); from Android
 * 14 onwards the system owns the switch while enabled ([MODE_SYSTEM_CONTROLLED])
 * or the engine stays silent ([MODE_DISABLED]).
 */
object KeyboardVibrationMode {
    const val MODE_APP_CONTROLLED: Int = 1
    const val MODE_SYSTEM_CONTROLLED: Int = 2
    const val MODE_DISABLED: Int = 3

    const val ACTION_KEYBOARD_VIBRATION_SETTINGS: String =
        "android.settings.KEYBOARD_VIBRATION_SETTINGS"

    const val SETTING_VIBRATE_ON: String = "vibrate_on"
    const val DEFAULT_VIBRATE_ON: Boolean = true
    const val SETTING_KEYBOARD_VIBRATION_ENABLED: String = "keyboard_vibration_enabled"
    const val DEFAULT_KEYBOARD_VIBRATION_ENABLED: Boolean = true
    const val SETTING_KEYBOARD_VIBRATION_INTENSITY: String = "keyboard_vibration_intensity"
    const val DEFAULT_KEYBOARD_VIBRATION_INTENSITY: Long = -1L

    private const val MODE_CACHE_MS = 2_000L

    @Volatile private var cachedMode: Int = -1
    @Volatile private var cachedModeAt: Long = 0L

    fun resolve(context: Context): Int {
        val now = android.os.SystemClock.uptimeMillis()
        cachedMode.let { if (it >= 0 && now - cachedModeAt < MODE_CACHE_MS) return it }
        val mode = resolveUncached(context)
        cachedMode = mode
        cachedModeAt = now
        return mode
    }

    private fun resolveUncached(context: Context): Int {
        if (resolveKeyboardVibrationIntent(context) == null) return MODE_APP_CONTROLLED
        val cr = context.contentResolver
        if (!SystemSettingsReader.systemBool(cr, SETTING_VIBRATE_ON, DEFAULT_VIBRATE_ON)) {
            return MODE_DISABLED
        }
        if (Build.VERSION.SDK_INT < 37) {
            if (!SystemSettingsReader.systemBool(
                    cr, SETTING_KEYBOARD_VIBRATION_ENABLED, DEFAULT_KEYBOARD_VIBRATION_ENABLED)
            ) return MODE_DISABLED
        } else {
            val intensity = SystemSettingsReader.systemLong(
                cr, SETTING_KEYBOARD_VIBRATION_INTENSITY, DEFAULT_KEYBOARD_VIBRATION_INTENSITY
            )
            if (intensity != -1L && intensity <= 0L) return MODE_DISABLED
        }
        return MODE_SYSTEM_CONTROLLED
    }

    // The probe hits PackageManager (an IPC); cache it for the process lifetime.
    @Volatile private var cachedSettingsIntent: Intent? = null
    @Volatile private var probeDone: Boolean = false

    /** SDK >= 34, pinned to com.android.settings, MATCH_DEFAULT_ONLY. */
    private fun resolveKeyboardVibrationIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        if (probeDone) return cachedSettingsIntent
        val intent = Intent(ACTION_KEYBOARD_VIBRATION_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appContext = try { context.applicationContext } catch (t: Throwable) { context }
        val resolved =
            if (appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                intent
            } else {
                null
            }
        cachedSettingsIntent = resolved
        probeDone = true
        return resolved
    }
}
