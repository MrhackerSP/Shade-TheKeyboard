package com.shade.keyboard.keypresseffect

import android.content.Context

/**
 * Lifecycle wiring for the press-effect engine:
 *
 *  - [onCreate]: construct the impl, install it into the global slot and
 *    cache system haptics below API 33. Mirroring the system keyboard-
 *    vibration state into the master pref is intentionally not done: Shade
 *    keeps the in-app toggle as the user's master switch instead.
 *  - [onDestroy]: swap the global slot back to the no-op player and close the
 *    impl (unregisters its pref listener).
 *
 * The system settings are not observed with ContentObserver-style listeners;
 * instead the mode is re-resolved on every effect — the observable behavior
 * (mode applied on the next key event after settings change) is equivalent
 * for a keyboard.
 */
object PressEffectModule {

    @Volatile
    private var installed: PressEffectPlayerImpl? = null

    /** Call from the IME service onCreate, after prefs are ready. */
    fun onCreate(context: Context, prefs: android.content.SharedPreferences) {
        val impl = PressEffectPlayerImpl(context, prefs)
        installed = impl
        PressEffectPlayer.install(impl)     // atomic swap + close old
        impl.refreshSystemHapticsState()
    }

    /** Call from the IME service onDestroy. */
    fun onDestroy() {
        installed = null
        PressEffectPlayer.uninstall()
    }
}
