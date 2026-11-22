package com.shade.keyboard.keypresseffect

/**
 * Device-tuning flags for the press-effect engine — a 1:1 port of Gboard's
 * `puh` flags holder (`okf` flag with optional sysprop override):
 *
 *  - `vibration_effect_min_sdk` — the primitive-composition path only runs when
 *    `Build.VERSION.SDK_INT >= vibration_effect_min_sdk`. Gboard ships this as
 *    **1024 (0x400)**, i.e. *effectively disabled until a server experiment
 *    lowers it*; on real Gboard cohorts the flag is fetched remotely and set
 *    to 30+ so the primitive path runs on capable hardware. Shade has no flag
 *    service, so we pin the post-experiment value (30) to match the behavior
 *    users actually experience on Gboard. Set [vibrationEffectMinSdk] back to
 *    1024 to replicate the factory-fallback behavior instead.
 *
 *  - `config_haptic_cutoff` — minimum interval between two haptic events
 *    (rate limit for the key-release effect; see `PressEffectPlayerImpl`).
 *    Default 200 ms, overridable through the sysprop
 *    `ro.com.google.ime.haptic.cutoff` (read through reflection on
 *    `android.os.SystemProperties`, exactly like Gboard's flag loader; any
 *    read failure silently keeps the default).
 */
object PressEffectFlags {
    /** Upstream ships 1024 (primitives off) and lowers it via server flag. */
    const val UPSTREAM_VIBRATION_EFFECT_MIN_SDK: Long = 1024L

    /**
     * Effective gate for the primitive-composition path. 30 = enabled on every
     * device that physically supports haptic primitives (Gboard post-flag).
     */
    @Volatile
    var vibrationEffectMinSdk: Long = 30L

    const val DEFAULT_HAPTIC_CUTOFF_MS: Long = 200L
    const val SYSPROP_HAPTIC_CUTOFF: String = "ro.com.google.ime.haptic.cutoff"

    /** Min interval between haptic events; sysprop override, default 200 ms. */
    val hapticCutoffMs: Long = readCutoffSysprop() ?: DEFAULT_HAPTIC_CUTOFF_MS

    /**
     * Gboard parses its default vibration duration from a string resource
     * (parsed with `Integer.parseInt`, falling back to -1 when absent).
     * Shade has no such resource; 50 is the reconstructed Gboard resource
     * default (the slider midpoint; range 0..100).
     */
    const val DEFAULT_VIBRATION_DURATION_MS: Int = 50

    private fun readCutoffSysprop(): Long? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        val value = (get.invoke(null, SYSPROP_HAPTIC_CUTOFF) as? String)?.trim().orEmpty()
        if (value.isEmpty()) null else value.toLongOrNull()
    } catch (t: Throwable) {
        null
    }
}
