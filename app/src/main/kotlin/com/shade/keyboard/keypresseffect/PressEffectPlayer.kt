package com.shade.keyboard.keypresseffect

import android.view.View

/**
 * Public API of the press-effect engine: a global singleton that every
 * keyboard view routes its haptics and key-press sounds through.
 *
 * A real [PressEffectPlayerImpl] is installed at IME startup and swaps the
 * global reference atomically; until then (and after teardown) the no-op
 * player is installed, so every call site is safe at any point of the process
 * lifecycle.
 */
interface PressEffectPlayer : AutoCloseable {

    /**
     * The router. [kind] values used across the keyboard's call sites:
     *
     *  - [KIND_BASIC_TAP] (0) — normal key press, toolbar buttons, long-press
     *    listeners;
     *  - [KIND_KEY_RELEASE] (1) — key release (`VIRTUAL_KEY_RELEASE`);
     *  - 2 — reserved, intentionally does nothing;
     *  - [KIND_TEXT_HANDLE] and any other value >= 3 — gesture / scrubbing /
     *    text-handle moves (`TEXT_HANDLE_MOVE`).
     */
    fun applyPressEffect(view: View?, kind: Int)

    /**
     * Plays a raw `HapticFeedbackConstants` value through the same gates —
     * used for slider ticks and other UI that already knows the exact constant
     * it wants.
     */
    fun applyHapticConstant(view: View?, constant: Int)

    /**
     * The custom effect builder: primitive composition with strength `value`%
     * on haptic-primitive hardware, `value`-ms one-shot otherwise. Also used
     * directly as the settings-slider live preview.
     */
    fun playCustomVibration(value: Int)

    /**
     * Key-press sound + tap haptic twin: plays the mapped `AudioManager` sound
     * effect when sound-on-keypress is enabled, then always routes
     * [KIND_BASIC_TAP] through [applyPressEffect]. [keyId] is the raw key
     * identifier (space/delete/enter ids get their specific sounds).
     */
    fun playKeySound(view: View?, keyId: Int)

    /** Gate for text-handle haptics. */
    fun canVibrateTextHandle(): Boolean

    /**
     * Slider label renderer: "N ms" on legacy devices, percentage on
     * haptic-primitive devices — the same custom pref value means strength or
     * duration. The slider's default state (no custom value) is rendered by
     * the settings UI as "System default" instead.
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

        // Key ids with dedicated press sounds.
        const val KEY_ID_SPACE: Int = 62
        const val KEY_ID_ENTER: Int = 66
        const val KEY_ID_DELETE: Int = 67
        const val KEY_ID_ACTION_RETURN: Int = -10018

        /** The no-op player: everything inert, labels empty. */
        val noop: PressEffectPlayer = object : PressEffectPlayer {
            override fun applyPressEffect(view: View?, kind: Int) {}
            override fun applyHapticConstant(view: View?, constant: Int) {}
            override fun playCustomVibration(value: Int) {}
            override fun playKeySound(view: View?, keyId: Int) {}
            override fun canVibrateTextHandle(): Boolean = false
            override fun formatVibrationLabel(value: Int): String = ""
            override fun close() {}
        }

        // Global accessor state (AtomicReference semantics).
        @Volatile
        private var current: PressEffectPlayer = noop

        /** The currently installed player, never null. */
        fun get(): PressEffectPlayer = current

        /**
         * Atomically swap the global player and close the previously installed
         * one (the impl's close unregisters listeners).
         */
        fun install(player: PressEffectPlayer) {
            val old = current
            current = player
            old.close()
        }

        /** Teardown: back to the no-op player. */
        fun uninstall() {
            install(noop)
        }
    }
}
