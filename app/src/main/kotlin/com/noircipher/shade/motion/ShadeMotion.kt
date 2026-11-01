package com.noircipher.shade.motion

import android.view.animation.PathInterpolator

object ShadeMotion {
    // Public Standard platform-style motion curves.
    val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)
    val FAST_OUT_LINEAR_IN = PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)

    // Key feedback.
    const val KEY_PRESS_DOWN_MS = 30L
    const val KEY_PRESS_UP_MS = 110L

    // Key popup / preview.
    const val KEY_POPUP_IN_MS = 55L
    const val KEY_POPUP_OUT_MS = 60L

    // Candidate strip.
    const val CANDIDATE_SWAP_MS = 110L

    // Keyboard layout changes.
    const val LAYOUT_FADE_OUT_MS = 80L
    const val LAYOUT_FADE_IN_MS = 130L
    const val MODE_CHANGE_MS = 180L

    // Swipe trail.
    const val SWIPE_TRAIL_FADE_IN_MS = 80L
    const val SWIPE_TRAIL_FADE_OUT_MS = 140L

    // Panels / clipboard / secondary UI.
    const val PANEL_IN_MS = 220L
    const val PANEL_OUT_MS = 170L

    // Small toggle/pulse feedback.
    const val UI_PRESS_IN_MS = 60L
    const val UI_TOGGLE_IN_MS = 90L
    const val UI_TOGGLE_OUT_MS = 140L
    const val FEEDBACK_BURST_MS = 360L
}
