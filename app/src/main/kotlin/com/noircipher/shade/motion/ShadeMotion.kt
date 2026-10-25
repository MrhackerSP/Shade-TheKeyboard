package com.noircipher.shade.motion

import android.view.animation.FastOutLinearInInterpolator
import android.view.animation.FastOutSlowInInterpolator

object ShadeMotion {
    // Public Android/Google motion curves.
    val FAST_OUT_SLOW_IN = FastOutSlowInInterpolator()
    val FAST_OUT_LINEAR_IN = FastOutLinearInInterpolator()

    // Key feedback.
    const val KEY_PRESS_DOWN_MS = 40L
    const val KEY_PRESS_UP_MS = 110L

    // Key popup / preview.
    const val KEY_POPUP_IN_MS = 90L
    const val KEY_POPUP_OUT_MS = 70L

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
