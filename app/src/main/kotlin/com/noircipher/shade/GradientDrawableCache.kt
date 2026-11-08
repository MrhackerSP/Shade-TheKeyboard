package com.noircipher.shade

import android.graphics.drawable.GradientDrawable

/** Reuses a single GradientDrawable instance for keyboard popups. */
object GradientDrawableCache {
    // A drawable shared between views gets mutated under them (and color 0 was never applied),
    // so hand out a fresh one per request.
    fun forKey(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
}
