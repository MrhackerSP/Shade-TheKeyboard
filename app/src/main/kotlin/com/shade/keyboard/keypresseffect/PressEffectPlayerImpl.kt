package com.shade.keyboard.keypresseffect

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_DISABLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_APP_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_SYSTEM_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.resolve

/**
 * The press-effect engine — haptics + key-press sounds for the whole keyboard.
 * Prefs (all read live through a change listener):
 *  `haptic`             -> master vibration toggle (default true)
 *  `vibration_duration` -> custom strength 0-100; absent = "System default"
 *  `sound`              -> key-press sound toggle (default false)
 *  `key_press_volume`   -> key press volume (default 1.0)
 */
open class PressEffectPlayerImpl(
    private val context: Context,
    private val prefs: SharedPreferences,
    audioManagerSupplier: () -> AudioManager? = {
        context.getSystemService("audio") as? AudioManager
    },
    vibratorSupplier: () -> Vibrator? = {
        @Suppress("DEPRECATION")
        context.getSystemService("vibrator") as? Vibrator
    },
    private val clock: () -> Long = SystemClock::uptimeMillis
) : PressEffectPlayer {

    private val audioManager: AudioManager? by lazy(audioManagerSupplier)
    private val vibrator: Vibrator? by lazy(vibratorSupplier)

    @Volatile protected var vibrateOn: Boolean = true
    @Volatile protected var soundOn: Boolean = false
    @Volatile protected var pressVolume: Float = 1.0f
    @Volatile protected var customStrength: Int = SYSTEM_DEFAULT_STRENGTH
    @Volatile internal var systemHapticsEnabled: Boolean = true
    @Volatile internal var lastHapticUptime: Long = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in WATCHED_PREFS) refreshFromPrefs()
    }

    init {
        refreshFromPrefs()
        runCatching { prefs.registerOnSharedPreferenceChangeListener(prefListener) }
    }

    fun refreshFromPrefs() {
        vibrateOn = prefs.getBoolean(PREF_VIBRATE, DEFAULT_VIBRATE_ON)
        soundOn = prefs.getBoolean(PREF_SOUND, DEFAULT_SOUND_ON)
        pressVolume = prefs.getFloat(PREF_VOLUME, DEFAULT_VOLUME)
        customStrength = if (prefs.contains(PREF_DURATION)) {
            prefs.getInt(PREF_DURATION, SYSTEM_DEFAULT_STRENGTH).coerceIn(0, MAX_STRENGTH)
        } else {
            SYSTEM_DEFAULT_STRENGTH
        }
    }

    fun refreshSystemHapticsState() {
        if (SDK_INT < 33) {
            systemHapticsEnabled = SystemSettingsReader.systemBool(
                context.contentResolver,
                SystemSettingsReader.SETTING_HAPTIC_FEEDBACK_ENABLED,
                true
            )
        }
    }

    override fun close() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }

    internal fun vibrationGate(): Boolean = SDK_INT >= 33 || systemHapticsEnabled

    internal fun rateLimitPassed(): Boolean = clock() - lastHapticUptime > HAPTIC_CUTOFF_MS

    // Primitive support is a binder call and cannot change at runtime: cache it.
    @Volatile private var primitivesSupported: Boolean? = null

    internal fun primitiveGate(v: Vibrator): Boolean {
        if (SDK_INT < 30) return false
        primitivesSupported?.let { return it }
        val supported = v.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            .firstOrNull() == true
        primitivesSupported = supported
        return supported
    }

    internal fun systemTapFallback(view: View) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: RuntimeException) {
        }
        lastHapticUptime = clock()
    }

    override fun applyPressEffect(view: View?, kind: Int) {
        if (view == null) return
        if (!vibrateOn) return
        if (kind == PressEffectPlayer.KIND_BASIC_TAP) {
            when (resolve(context)) {
                MODE_APP_CONTROLLED -> {
                    val strength = customStrength
                    when {
                        strength == SYSTEM_DEFAULT_STRENGTH ->
                            if (vibrationGate()) systemTapFallback(view)
                        strength > 0 -> {
                            playCustomVibration(strength)
                            lastHapticUptime = clock()
                        }
                    }
                }
                MODE_SYSTEM_CONTROLLED -> systemTapFallback(view)
                // MODE_DISABLED -> silent
            }
        } else if (kind == PressEffectPlayer.KIND_KEY_RELEASE) {
            when (resolve(context)) {
                MODE_APP_CONTROLLED -> {
                    if (customStrength == SYSTEM_DEFAULT_STRENGTH &&
                        (SDK_INT >= 33 || systemHapticsEnabled) &&
                        SDK_INT >= 27 && rateLimitPassed()
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    }
                }
                MODE_SYSTEM_CONTROLLED -> {
                    if (SDK_INT >= 27 && rateLimitPassed()) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    }
                }
                // MODE_DISABLED -> silent
            }
        } else if (kind != 2) {
            if (canVibrateTextHandle()) {
                view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            }
        }
        // kind == 2: intentionally nothing
    }

    override fun applyHapticConstant(view: View?, constant: Int) {
        try {
            if (view != null) {
                when (resolve(context)) {
                    MODE_APP_CONTROLLED -> if (vibrationGate()) {
                        view.performHapticFeedback(constant)
                    }
                    MODE_SYSTEM_CONTROLLED -> view.performHapticFeedback(constant)
                }
            }
        } catch (_: RuntimeException) {
        }
    }

    override fun playCustomVibration(value: Int) {
        val v = vibrator ?: return
        try {
            val effect = if (SDK_INT >= 30 && primitiveGate(v)) {
                VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        value * PRIMITIVE_SCALE_STEP
                    )
                    .compose()
            } else {
                VibrationEffect.createOneShot(value.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            }
            if (SDK_INT < 33) {
                v.vibrate(effect)
            } else {
                // USAGE_TOUCH, not USAGE_MEDIA: key haptics must follow the
                // touch-vibration setting, not the media one.
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            }
        } catch (_: RuntimeException) {
        }
    }

    override fun playKeySound(view: View?, keyId: Int) {
        if (soundOn) {
            audioManager?.playSoundEffect(soundEffectFor(keyId), pressVolume)
        }
        applyPressEffect(view, PressEffectPlayer.KIND_BASIC_TAP)
    }

    override fun canVibrateTextHandle(): Boolean {
        if (SDK_INT < 27) return false
        return when (resolve(context)) {
            MODE_APP_CONTROLLED -> vibrationGate()
            MODE_SYSTEM_CONTROLLED -> true
            else -> false
        }
    }

    override fun formatVibrationLabel(value: Int): String {
        val v = vibrator
        return if (v == null || !primitiveGate(v)) {
            "$value ms"
        } else {
            "$value%"
        }
    }

    /** Key-id -> press-sound map: space/delete/enter get dedicated sounds, rest standard. */
    internal fun soundEffectFor(keyId: Int): Int = when (keyId) {
        PressEffectPlayer.KEY_ID_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
        PressEffectPlayer.KEY_ID_DELETE -> AudioManager.FX_KEYPRESS_DELETE
        PressEffectPlayer.KEY_ID_ENTER, PressEffectPlayer.KEY_ID_ACTION_RETURN ->
            AudioManager.FX_KEYPRESS_RETURN
        else -> AudioManager.FX_KEYPRESS_STANDARD
    }

    companion object {
        const val PREF_VIBRATE: String = "haptic"
        const val PREF_SOUND: String = "sound"
        const val PREF_DURATION: String = "vibration_duration"
        const val PREF_VOLUME: String = "key_press_volume"
        const val DEFAULT_VIBRATE_ON: Boolean = true
        const val DEFAULT_SOUND_ON: Boolean = false
        const val DEFAULT_VOLUME: Float = 1.0f
        const val SYSTEM_DEFAULT_STRENGTH: Int = -1
        const val MAX_STRENGTH: Int = 100
        const val PRIMITIVE_SCALE_STEP: Float = 0.01f
        const val HAPTIC_CUTOFF_MS: Long = 200L
        val WATCHED_PREFS: Set<String> = setOf(PREF_VIBRATE, PREF_SOUND, PREF_DURATION, PREF_VOLUME)
    }
}
