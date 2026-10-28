package com.noircipher.shade

import android.graphics.drawable.GradientDrawable

/** Reuses a single GradientDrawable instance for keyboard popups. */
object GradientDrawableCache {
    private var cached: GradientDrawable? = null
    private var lastColor = 0
    private var lastRadius = 0f

    fun forKey(color: Int, radius: Float): GradientDrawable {
        val d = cached ?: GradientDrawable().also { cached = it }
        if (color != lastColor) {
            d.setColor(color)
            lastColor = color
        }
        if (radius != lastRadius) {
            d.cornerRadius = radius
            lastRadius = radius
        }
        return d
    }
}
