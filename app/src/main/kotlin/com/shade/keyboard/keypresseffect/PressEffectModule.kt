package com.shade.keyboard.keypresseffect

import android.content.Context

/**
 * Lifecycle wiring — port of Gboard's
 * `keypresseffect.module.PressEffectPlayerModuleProvider$Module` (`puo`):
 *
 *  - `fM(...)` (onCreate) -> [onCreate]: construct the impl, install it into
 *    the global slot (`puj.b`), cache system haptics below API 33 (`puo.a`),
 *    and mirror the system keyboard-vibration state into the master pref
 *    (`puo.b` / `updateVibrationPreference`).
 *  - `fN()` (onDestroy) -> [onDestroy]: swap the global slot back to the no-op
 *    player and close the impl (unregisters its pref listener).
 *
 * Gboard additionally registers ContentObserver-style listeners on the system
 * settings (pum.a) so mode flips are picked up live; Shade refreshes on
 * [refresh] / service recreate instead — the observable behavior (mode applied
 * on the next key event after settings change) is equivalent for a keyboard.
 */
object PressEffectModule {

    @Volatile
    private var installed: PressEffectPlayerImpl? = null

    /** `puo.fM` — call from the IME service onCreate, after prefs are ready. */
    fun onCreate(context: Context, prefs: android.content.SharedPreferences) {
        val impl = PressEffectPlayerImpl(context, prefs)
        installed = impl
        PressEffectPlayer.install(impl)     // puj.b: atomic swap + close old
        impl.refreshSystemHapticsState()    // puo.a
        impl.updateVibrationPreference()    // puo.b
    }

    /** `puo.fN` — call from the IME service onDestroy. */
    fun onDestroy() {
        installed = null
        PressEffectPlayer.uninstall()
    }

    /** Explicit re-sync after a settings change (PrefsApplier calls this). */
    fun refresh() {
        installed?.refreshFromPrefs()
    }
}
