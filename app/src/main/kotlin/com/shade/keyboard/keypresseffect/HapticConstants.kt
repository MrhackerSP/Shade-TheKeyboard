package com.shade.keyboard.keypresseffect

import android.os.Build

/**
 * Haptic constant values mirrored from android.view.HapticFeedbackConstants,
 * exactly as Gboard's `ser`/`ses` resolver does.
 *
 * Gboard reflects the constants on modern devices and falls back to the same
 * literal ints on SDK <= 26 — the values are frozen framework constants, so we
 * use the literals directly (identical behavior, no reflection cost):
 *
 *  - [KEYBOARD_TAP]        = 3 (API 1)
 *  - [VIRTUAL_KEY_RELEASE] = 7 (API 25)
 *  - [TEXT_HANDLE_MOVE]    = 9 (API 27)
 *
 * [hasHandleConstants] mirrors Gboard's `ser.a` / `ses.n()` gate: the release
 * and text-handle effects require the constants to exist on the running OS.
 * TEXT_HANDLE_MOVE is the newest of the two (API 27), so availability == O MR1+.
 */
object HapticConstants {
    const val KEYBOARD_TAP: Int = 3
    const val VIRTUAL_KEY_RELEASE: Int = 7
    const val TEXT_HANDLE_MOVE: Int = 9

    /** True when TEXT_HANDLE_MOVE / VIRTUAL_KEY_RELEASE exist on this OS (API 27+). */
    fun hasHandleConstants(): Boolean = Build.VERSION.SDK_INT >= 27
}
