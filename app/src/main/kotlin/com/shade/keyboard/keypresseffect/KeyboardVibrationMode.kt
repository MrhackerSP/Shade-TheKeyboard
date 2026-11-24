package com.shade.keyboard.keypresseffect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Intensity-mode resolver — decides whether the keyboard drives key-press
 * vibration itself or defers to the system's keyboard-vibration setting.
 *
 * Resolution:
 *
 * ```
 * static int b(Context context) {
 *     if (resolveKeyboardVibrationIntent(context) == null) return 1;   // no settings UI
 *     SystemSettings s = SystemSettings.get(context);
 *     if (!s.vibrateOn())                    return 3;                 // system master off
 *     if (SDK < 37) {
 *         if (!s.keyboardVibrationEnabled()) return 3;                 // keyboard vib off
 *     } else {
 *         long intensity = s.keyboardVibrationIntensity();
 *         if (intensity != -1 && intensity <= 0) return 3;             // intensity off
 *     }
 *     return 2;                                                        // system controls
 * }
 * ```
 *
 * The settings-screen probe:
 *
 * ```
 * if (SDK >= 34) {
 *     Intent i = new Intent("android.settings.KEYBOARD_VIBRATION_SETTINGS");
 *     i.setPackage("com.android.settings");
 *     if (pm.resolveActivity(i, MATCH_DEFAULT_ONLY) != null) return i;
 * }
 * return null;
 * ```
 *
 * So on Android < 14 the keyboard-vibration settings screen does not exist and
 * the keyboard always drives vibration itself (mode [MODE_APP_CONTROLLED]);
 * from Android 14 onwards the system owns the switch while enabled (mode
 * [MODE_SYSTEM_CONTROLLED]) or the engine stays silent (mode [MODE_DISABLED]).
 */
object KeyboardVibrationMode {
    /** No keyboard-vibration settings UI on the device — the keyboard drives vibration itself. */
    const val MODE_APP_CONTROLLED: Int = 1

    /** Android 14+ keyboard vibration active — defer to `View.performHapticFeedback`. */
    const val MODE_SYSTEM_CONTROLLED: Int = 2

    /** Settings UI present but keyboard vibration switched off — the engine stays silent. */
    const val MODE_DISABLED: Int = 3

    const val ACTION_KEYBOARD_VIBRATION_SETTINGS: String =
        "android.settings.KEYBOARD_VIBRATION_SETTINGS"

    // Settings.System rows: (key, default).
    const val SETTING_VIBRATE_ON: String = "vibrate_on"
    const val DEFAULT_VIBRATE_ON: Boolean = true

    const val SETTING_KEYBOARD_VIBRATION_ENABLED: String = "keyboard_vibration_enabled"
    const val DEFAULT_KEYBOARD_VIBRATION_ENABLED: Boolean = true

    const val SETTING_KEYBOARD_VIBRATION_INTENSITY: String = "keyboard_vibration_intensity"
    const val DEFAULT_KEYBOARD_VIBRATION_INTENSITY: Long = -1L

    fun resolve(context: Context): Int {
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

    // The probe hits PackageManager (an IPC); cache it for the process
    // lifetime — whether the system exposes the keyboard-vibration screen
    // cannot meaningfully change while a keyboard session is running.
    @Volatile private var cachedSettingsIntent: Intent? = null
    @Volatile private var probeDone: Boolean = false

    /** SDK >= 34, pinned to com.android.settings, MATCH_DEFAULT_ONLY. */
    private fun resolveKeyboardVibrationIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        if (probeDone) return cachedSettingsIntent
        val intent = Intent(ACTION_KEYBOARD_VIBRATION_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appContext = try { context.getApplicationContext() } catch (t: Throwable) { context }
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
