package com.shade.keyboard.keypresseffect

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_DISABLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_GBOARD_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.MODE_SYSTEM_CONTROLLED
import com.shade.keyboard.keypresseffect.KeyboardVibrationMode.resolve
import java.text.NumberFormat

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
 *  | `l()`    | [durationIsCustomized]      | user changed the duration from default   |
 *  | `i()`    | [recomputeDuration]         | cached duration recompute (cap at 100)   |
 *  | `c(I)`   | [formatVibrationLabel]      | "N ms" vs "N%" slider label              |
 *
 * Pref mapping (Gboard logical name -> Shade pref key):
 *  - `enable_vibrate_on_keypress` -> "haptic"        (default true)
 *  - `vibration_duration`         -> "vibration_duration" (default = system default)
 *  - `enable_sound_on_keypress`   -> "sound"         (default false)
 *  - key press volume             -> "key_press_volume"   (default 1.0)
 */
open class PressEffectPlayerImpl(
    private val context: Context,
    private val prefs: SharedPreferences,
    /** Gboard parses this from a string resource; -1 = resource absent. */
    overrideSystemDefaultDuration: Int = PressEffectFlags.DEFAULT_VIBRATION_DURATION_MS,
    /** `puh.b` — min interval between haptic events (sysprop-overridable). */
    internal val cutoffMs: Long = PressEffectFlags.hapticCutoffMs,
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
    @Volatile protected var systemDefaultDuration: Int = overrideSystemDefaultDuration
    @Volatile protected var oemDisabled: Boolean = false        // pun.m
    @Volatile protected var vibrateOn: Boolean = true           // pun.d
    @Volatile protected var soundOn: Boolean = false            // pun.e
    @Volatile protected var pressVolume: Float = 1.0f           // pun.f
    @Volatile protected var duration: Int = 0                   // pun.n
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
        oemDisabled = prefs.contains(PREF_VIBRATE) && !prefs.getBoolean(PREF_VIBRATE, false)
        vibrateOn = prefs.getBoolean(PREF_VIBRATE, DEFAULT_VIBRATE_ON)
        soundOn = prefs.getBoolean(PREF_SOUND, DEFAULT_SOUND_ON)
        pressVolume = prefs.getFloat(PREF_VOLUME, DEFAULT_VOLUME)
        recomputeDuration()
    }

    /** `pun.i()` — effective duration = min(pref or system default, 100). */
    fun recomputeDuration() {
        val raw = if (!oemDisabled) {
            prefs.getInt(PREF_DURATION, systemDefaultDuration)
        } else {
            systemDefaultDuration
        }
        duration = minOf(raw, MAX_DURATION_MS)
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

    /** `puo.b()` — mirror system keyboard-vibration state into the master pref. */
    fun updateVibrationPreference() {
        val mode = resolve(context)
        if (mode != MODE_GBOARD_CONTROLLED) {
            prefs.edit().putBoolean(PREF_VIBRATE, mode == MODE_SYSTEM_CONTROLLED).apply()
        }
    }

    override fun close() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }

    // ---- gates -------------------------------------------------------------

    /** `pun.n()` — vibration allowed: master pref on, plus OS-era conditions. */
    internal fun vibrationGate(): Boolean {
        if (!vibrateOn) return false
        return SDK_INT >= 33 || systemHapticsEnabled || durationIsCustomized()
    }

    /** `pun.l()` — the user moved the duration away from the system default. */
    internal fun durationIsCustomized(): Boolean = systemDefaultDuration != duration

    /** `pun.j()` — enough time has passed since the last haptic event. */
    internal fun rateLimitPassed(): Boolean = clock() - lastHapticUptime > cutoffMs

    /** `pun.k(Vibrator)` — SDK >= 30, flag gate, PRIMITIVE_CLICK supported. */
    internal fun primitiveGate(v: Vibrator): Boolean {
        if (SDK_INT < 30) return false
        if (SDK_INT < PressEffectFlags.vibrationEffectMinSdk) return false
        return v.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            .firstOrNull() == true
    }

    // ---- effects -----------------------------------------------------------

    /** `pun.m(View)` — KEYBOARD_TAP fallback; also records the haptic timestamp. */
    internal fun systemTapFallback(view: View) {
        try {
            view.performHapticFeedback(HapticConstants.KEYBOARD_TAP)
        } catch (_: RuntimeException) {
        }
        lastHapticUptime = clock()
    }

    override fun applyPressEffect(view: View?, kind: Int) {
        if (view == null) return
        if (kind == PressEffectPlayer.KIND_BASIC_TAP) {
            // performBasicTapEffect
            when (resolve(context)) {
                MODE_GBOARD_CONTROLLED -> {
                    if (vibrationGate()) {
                        if (systemDefaultDuration != -1 || durationIsCustomized()) {
                            val dur = duration
                            if (dur > 0) {
                                playCustomVibration(dur)
                                lastHapticUptime = clock()
                            }
                        } else {
                            // No default duration resource and nothing customized:
                            // plain system tap, exactly like upstream.
                            systemTapFallback(view)
                        }
                    }
                }
                MODE_SYSTEM_CONTROLLED -> systemTapFallback(view)
                // MODE_DISABLED -> silent (upstream: no else branch)
            }
        } else if (kind == PressEffectPlayer.KIND_KEY_RELEASE) {
            // performKeyReleaseEffect
            when (resolve(context)) {
                MODE_GBOARD_CONTROLLED -> {
                    if (vibrateOn && (SDK_INT >= 33 || systemHapticsEnabled) &&
                        !durationIsCustomized() &&
                        HapticConstants.hasHandleConstants() && rateLimitPassed()
                    ) {
                        view.performHapticFeedback(HapticConstants.VIRTUAL_KEY_RELEASE)
                    }
                }
                MODE_SYSTEM_CONTROLLED -> {
                    if (HapticConstants.hasHandleConstants() && rateLimitPassed()) {
                        view.performHapticFeedback(HapticConstants.VIRTUAL_KEY_RELEASE)
                    }
                }
                // MODE_DISABLED -> silent
            }
        } else if (kind != 2) {
            // performHapticTextHandleEffect (any kind except 0, 1, 2)
            if (canVibrateTextHandle()) {
                view.performHapticFeedback(HapticConstants.TEXT_HANDLE_MOVE)
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
            // upstream `k(Vibrator)` = SDK >= 30 && SDK >= vibration_effect_min_sdk
            // && CLICK primitive supported. Branch bodies are upstream `f(I)`
            // verbatim: composition at value*0.01f, else one-shot of `value` ms.
            val effect = if (SDK_INT >= 30 &&
                SDK_INT >= PressEffectFlags.vibrationEffectMinSdk &&
                primitiveGate(v)
            ) {
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
        if (!HapticConstants.hasHandleConstants()) return false
        return when (resolve(context)) {
            MODE_GBOARD_CONTROLLED -> vibrationGate()
            MODE_SYSTEM_CONTROLLED -> true
            else -> false
        }
    }

    override fun systemDefaultVibrationDuration(): Int = systemDefaultDuration

    override fun vibrationDuration(): Int = duration

    override fun formatVibrationLabel(value: Int): String {
        val v = vibrator
        return if (v == null || !primitiveGate(v)) {
            // Legacy: the same pref value is a duration in milliseconds.
            val nf = NumberFormat.getNumberInstance()
            nf.isGroupingUsed = false
            "$value ms"
        } else {
            // Primitive hardware: the same pref value is a strength percentage.
            val pf = NumberFormat.getPercentInstance()
            pf.maximumFractionDigits = 0
            pf.format(value * PRIMITIVE_SCALE_STEP.toDouble())
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

        /** Slider/strength range cap (upstream `Math.min(v, 100)`). */
        const val MAX_DURATION_MS: Int = 100

        /** Upstream `value * 0.01f`: slider % -> primitive scale. */
        const val PRIMITIVE_SCALE_STEP: Float = 0.01f

        val WATCHED_PREFS: Set<String> = setOf(PREF_VIBRATE, PREF_SOUND, PREF_DURATION, PREF_VOLUME)
    }
}
