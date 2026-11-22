package com.shade.keyboard.keypresseffect

import android.view.View

/**
 * Public API of the press-effect engine — port of Gboard's
 * `keypresseffect.PressEffectPlayer` (`pul`) interface and its global
 * singleton plumbing (`puj` accessor + `puk` no-op default).
 *
 * Gboard installs a real [PressEffectPlayerImpl] at IME startup
 * (`PressEffectPlayerModuleProvider$Module.onCreate`) and swaps the global
 * reference atomically; until then (and after teardown) the no-op player is
 * installed, so every call site is safe at any point of the process lifecycle.
 */
interface PressEffectPlayer : AutoCloseable {

    /**
     * The router (`pul.d` / `pun.d`). [kind] values as used by Gboard's ~25
     * call sites:
     *
     *  - [KIND_BASIC_TAP] (0) — normal key press, toolbar buttons, long-press
     *    listeners;
     *  - [KIND_KEY_RELEASE] (1) — key release (`VIRTUAL_KEY_RELEASE`);
     *  - 2 — reserved, intentionally does nothing (matches upstream);
     *  - [KIND_TEXT_HANDLE] and any other value >= 3 — gesture / scrubbing /
     *    text-handle moves (`TEXT_HANDLE_MOVE`).
     */
    fun applyPressEffect(view: View?, kind: Int)

    /**
     * Plays a raw `HapticFeedbackConstants` value through the same gates
     * (`pul.e` / `pun.e`) — used by Gboard for slider ticks and other UI that
     * already knows the exact constant it wants.
     */
    fun applyHapticConstant(view: View?, constant: Int)

    /**
     * The custom effect builder (`pul.f` / `pun.f`): primitive composition with
     * strength `value`% on haptic-primitive hardware, `value`-ms one-shot
     * otherwise. Also used directly as the settings-slider live preview.
     */
    fun playCustomVibration(value: Int)

    /**
     * Key-press sound + tap haptic twin (`pul.g` / `pun.g`): plays the mapped
     * `AudioManager` sound effect when sound-on-keypress is enabled, then
     * always routes [KIND_BASIC_TAP] through [applyPressEffect]. [keyId] is the
     * raw key identifier (space/delete/enter ids get their specific sounds).
     */
    fun playKeySound(view: View?, keyId: Int)

    /** Gate for text-handle haptics (`pul.h` / `pun.h`). */
    fun canVibrateTextHandle(): Boolean

    /** The resource-parsed system default duration (`pul.a`), -1 when absent. */
    fun systemDefaultVibrationDuration(): Int

    /** The currently cached effective duration (`pul.b`). */
    fun vibrationDuration(): Int

    /**
     * Slider label renderer (`pul.c`): "N ms" on legacy devices, percentage on
     * haptic-primitive devices — the same pref value means strength or duration.
     */
    fun formatVibrationLabel(value: Int): String

    override fun close()

    companion object {
        /** Normal key press. */
        const val KIND_BASIC_TAP: Int = 0
        /** Key release. */
        const val KIND_KEY_RELEASE: Int = 1
        /** Gesture / scrubbing / text-handle moves (kind >= 3). */
        const val KIND_TEXT_HANDLE: Int = 3

        // Key ids with dedicated press sounds (Gboard `u` map inputs).
        const val KEY_ID_SPACE: Int = 62
        const val KEY_ID_ENTER: Int = 66
        const val KEY_ID_DELETE: Int = 67
        const val KEY_ID_GBOARD_RETURN: Int = -10018

        /** The no-op player (`puk`): a()=b()=-1, c()="", everything else inert. */
        val noop: PressEffectPlayer = object : PressEffectPlayer {
            override fun applyPressEffect(view: View?, kind: Int) {}
            override fun applyHapticConstant(view: View?, constant: Int) {}
            override fun playCustomVibration(value: Int) {}
            override fun playKeySound(view: View?, keyId: Int) {}
            override fun canVibrateTextHandle(): Boolean = false
            override fun systemDefaultVibrationDuration(): Int = -1
            override fun vibrationDuration(): Int = -1
            override fun formatVibrationLabel(value: Int): String = ""
            override fun close() {}
        }

        // Global accessor state (`pul.b` AtomicReference + `puj` semantics).
        @Volatile
        private var current: PressEffectPlayer = noop

        /** `puj.a()` — the currently installed player, never null. */
        fun get(): PressEffectPlayer = current

        /**
         * `puj.b(player)` — atomically swap the global player and close the
         * previously installed one (the impl's close unregisters listeners).
         */
        fun install(player: PressEffectPlayer) {
            val old = current
            current = player
            old.close()
        }

        /** `puo.fN` teardown: back to the no-op player. */
        fun uninstall() {
            install(noop)
        }
    }
}
