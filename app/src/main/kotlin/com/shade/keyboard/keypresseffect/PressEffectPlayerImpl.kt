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
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_GBOARD_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_SYSTEM_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.resolve

/**
 * The press-effect engine — a faithful port of Gboard 18.5.10's
 * `keypresseffect.module.PressEffectPlayerImpl` (`pun`), reconstructed method
 * by method from the decompiled smali:
 *
 *  | Gboard | here                          | role                                     |
 *  |--------|-------------------------------|------------------------------------------|
 *  | `d(V,I)` | [applyPressEffect]          | router: tap / release / text-handle      |
 *  | `e(V,I)` | [applyHapticConstant]       | raw-constant path (slider ticks etc.)    |
 *  | `f(I)`   | [playCustomVibration]       | primitive/one-shot effect builder        |
 *  | `g(V,I)` | [playKeySound]              | press sound + basic tap                  |
 *  | `k(V)`   | [primitiveGate]             | primitive support gate                   |
 *  | `m(V)`   | [systemTapFallback]         | `performHapticFeedback(KEYBOARD_TAP)`    |
 *  | `n()`    | [vibrationGate]             | "is vibration currently allowed"         |
 *  | `j()`    | [rateLimitPassed]           | cutoff rate limiter (200 ms default)     |
 *  | `c(I)`   | [formatVibrationLabel]      | "N ms" vs "N%" slider label              |
 *
 * Pref mapping (Gboard logical name -> Shade pref key):
 *  - `enable_vibrate_on_keypress` -> "haptic"        (default true)
 *  - `vibration_duration`         -> "vibration_duration"
 *     Absent = Gboard slider's default state, **"System default"**: the OS's
 *     own keyboard-tap haptic (`performHapticFeedback(KEYBOARD_TAP)` — the
 *     device-tuned tick, gated/scaled by the system exactly like Gboard on
 *     Android 14+). A custom value 0-100 plays Gboard's `f(I)` effect:
 *     PRIMITIVE_CLICK at value% on haptic-primitive hardware, a value-ms
 *     one-shot otherwise.
 *  - `enable_sound_on_keypress`   -> "sound"         (default false)
 *  - key press volume             -> "key_press_volume"   (default 1.0)
 */
open class PressEffectPlayerImpl(
    private val context: Context,
    private val prefs: SharedPreferences,
    audioManagerSupplier: () -> AudioManager? = {
        context.getSystemService("audio") as? AudioManager
    },
    /** `mqt` case 16: the raw "vibrator" string service, exactly like Gboard. */
    vibratorSupplier: () -> Vibrator? = {
        @Suppress("DEPRECATION")
        context.getSystemService("vibrator") as? Vibrator
    },
    private val clock: () -> Long = SystemClock::uptimeMillis
) : PressEffectPlayer {

    private val audioManager: AudioManager? by lazy(audioManagerSupplier)
    private val vibrator: Vibrator? by lazy(vibratorSupplier)

    // ---- cached state (mirrors pun's fields) -------------------------------
    @Volatile protected var vibrateOn: Boolean = true           // pun.d
    @Volatile protected var soundOn: Boolean = false            // pun.e
    @Volatile protected var pressVolume: Float = 1.0f           // pun.f
    /**
     * Gboard's "Vibration strength on keypress" value: 0-100 when the user
     * picked a custom level, [SYSTEM_DEFAULT_STRENGTH] for the slider's
     * default state (the OS keyboard tap).
     */
    @Volatile protected var customStrength: Int = SYSTEM_DEFAULT_STRENGTH
    @Volatile internal var systemHapticsEnabled: Boolean = true // pun.g
    @Volatile internal var lastHapticUptime: Long = 0L          // pun.p

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // pun's fgy cases 13-16: sound / vibrate / volume / duration changes
        // each refresh the corresponding cached field; refreshing everything is
        // equivalent and keeps a single listener.
        if (key in WATCHED_PREFS) refreshFromPrefs()
    }

    init {
        refreshFromPrefs()
        runCatching { prefs.registerOnSharedPreferenceChangeListener(prefListener) }
    }

    // ---- preference plumbing ----------------------------------------------

    /** Re-reads every pref-backed field (pun constructor + fgy 13/14/15/16). */
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

    /** `puo.a()` — cache system haptic state below API 33 (`haptic_feedback_enabled`). */
    fun refreshSystemHapticsState() {
        if (SDK_INT < 33) {
            systemHapticsEnabled = SystemSettingsReader.systemBool(
                context.contentResolver,
                SystemSettingsReader.SETTING_HAPTIC_FEEDBACK_ENABLED,
                true
            )
        }
    }

    /**
     * `puo.b` in upstream mirrors the system keyboard-vibration state into the
     * master pref on Android 14+. Shade intentionally does NOT: the in-app
     * toggle stays the user's master switch, and the system keyboard-vibration
     * settings still govern through [KeyboardVibrationMode.resolve].
     */

    override fun close() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }

    // ---- gates -------------------------------------------------------------

    /**
     * `pun.n()` — global gate for the system-tap path (the "System default"
     * state). A custom strength intentionally bypasses it, matching upstream:
     * a user-picked strength plays even with the global haptics toggle off.
     * The in-app master switch is checked at the top of [applyPressEffect].
     */
    internal fun vibrationGate(): Boolean = SDK_INT >= 33 || systemHapticsEnabled

    /** `pun.j()` — enough time has passed since the last haptic event. */
    internal fun rateLimitPassed(): Boolean = clock() - lastHapticUptime > HAPTIC_CUTOFF_MS

    /** `pun.k(Vibrator)` — SDK >= 30, PRIMITIVE_CLICK supported. */
    internal fun primitiveGate(v: Vibrator): Boolean {
        if (SDK_INT < 30) return false
        return v.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            .firstOrNull() == true
    }

    // ---- effects -----------------------------------------------------------

    /** `pun.m(View)` — KEYBOARD_TAP fallback; also records the haptic timestamp. */
    internal fun systemTapFallback(view: View) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: RuntimeException) {
        }
        lastHapticUptime = clock()
    }

    override fun applyPressEffect(view: View?, kind: Int) {
        if (view == null) return
        // Shade keeps the in-app toggle authoritative in every mode (upstream
        // puo.b mirrors the system state into this pref on Android 14+; without
        // that mirroring the switch must gate here or it would do nothing).
        if (!vibrateOn) return
        if (kind == PressEffectPlayer.KIND_BASIC_TAP) {
            // performBasicTapEffect
            when (resolve(context)) {
                MODE_GBOARD_CONTROLLED -> {
                    val strength = customStrength
                    when {
                        // Slider default state — "System default": the OS's own
                        // keyboard tap. This is Gboard's out-of-box feel and the
                        // crisp device-tuned tick (never a long one-shot).
                        strength == SYSTEM_DEFAULT_STRENGTH ->
                            if (vibrationGate()) systemTapFallback(view)
                        // Custom strength 1-100: Gboard's f(I) — primitive click
                        // at strength% on capable hardware, N-ms one-shot otherwise.
                        strength > 0 -> {
                            playCustomVibration(strength)
                            lastHapticUptime = clock()
                        }
                        // strength == 0: the slider floor — silent.
                    }
                }
                MODE_SYSTEM_CONTROLLED -> systemTapFallback(view)
                // MODE_DISABLED -> silent (upstream: no else branch)
            }
        } else if (kind == PressEffectPlayer.KIND_KEY_RELEASE) {
            // performKeyReleaseEffect — only in the "System default" state
            // (upstream skips release ticks once a custom strength is set).
            when (resolve(context)) {
                MODE_GBOARD_CONTROLLED -> {
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
            // performHapticTextHandleEffect (any kind except 0, 1, 2)
            if (canVibrateTextHandle()) {
                view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            }
        }
        // kind == 2: intentionally nothing (upstream parity)
    }

    override fun applyHapticConstant(view: View?, constant: Int) {
        try {
            if (view != null) {
                when (resolve(context)) {
                    MODE_GBOARD_CONTROLLED -> if (vibrationGate()) {
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
            // Inline SDK gate (lint-visible; an opaque helper check is not):
            // upstream `k(Vibrator)` = SDK >= 30 && CLICK primitive supported.
            // Branch bodies are upstream `f(I)` verbatim: composition at
            // value*0.01f, else one-shot of `value` ms.
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
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            }
        } catch (_: RuntimeException) {
        }
    }

    override fun playKeySound(view: View?, keyId: Int) {
        if (soundOn) {
            // Gboard: sound effect id 0 stays 0 (boxed), then u.getOrDefault(id, 5).
            audioManager?.playSoundEffect(soundEffectFor(keyId), pressVolume)
        }
        applyPressEffect(view, PressEffectPlayer.KIND_BASIC_TAP)
    }

    override fun canVibrateTextHandle(): Boolean {
        if (SDK_INT < 27) return false
        return when (resolve(context)) {
            MODE_GBOARD_CONTROLLED -> vibrationGate()
            MODE_SYSTEM_CONTROLLED -> true
            else -> false
        }
    }

    override fun formatVibrationLabel(value: Int): String {
        val v = vibrator
        return if (v == null || !primitiveGate(v)) {
            // Legacy: the same pref value is a duration in milliseconds.
            "$value ms"
        } else {
            // Primitive hardware: the same pref value is a strength percentage.
            "$value%"
        }
    }

    /** Gboard `u` map: 62->6 (spacebar), 67->7 (delete), 66/(-10018)->8 (return); default 5. */
    internal fun soundEffectFor(keyId: Int): Int = when (if (keyId != 0) keyId else 0) {
        PressEffectPlayer.KEY_ID_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
        PressEffectPlayer.KEY_ID_DELETE -> AudioManager.FX_KEYPRESS_DELETE
        PressEffectPlayer.KEY_ID_ENTER, PressEffectPlayer.KEY_ID_GBOARD_RETURN ->
            AudioManager.FX_KEYPRESS_RETURN
        else -> AudioManager.FX_KEYPRESS_STANDARD
    }

    companion object {
        // Gboard logical pref names -> Shade pref keys.
        const val PREF_VIBRATE: String = "haptic"                   // enable_vibrate_on_keypress
        const val PREF_SOUND: String = "sound"                      // enable_sound_on_keypress
        const val PREF_DURATION: String = "vibration_duration"      // vibration_duration
        const val PREF_VOLUME: String = "key_press_volume"          // key press sound volume

        const val DEFAULT_VIBRATE_ON: Boolean = true
        const val DEFAULT_SOUND_ON: Boolean = false
        const val DEFAULT_VOLUME: Float = 1.0f

        /**
         * Gboard's slider default state: no custom strength picked — haptics
         * defer to the OS keyboard tap. The pref key being absent means this.
         */
        const val SYSTEM_DEFAULT_STRENGTH: Int = -1

        /** Slider ceiling (upstream `Math.min(v, 100)`). */
        const val MAX_STRENGTH: Int = 100

        /** Upstream `value * 0.01f`: slider % -> primitive scale. */
        const val PRIMITIVE_SCALE_STEP: Float = 0.01f

        /** `puh.b` default — min interval between haptic events (release ticks). */
        const val HAPTIC_CUTOFF_MS: Long = 200L

        val WATCHED_PREFS: Set<String> = setOf(PREF_VIBRATE, PREF_SOUND, PREF_DURATION, PREF_VOLUME)
    }
}
