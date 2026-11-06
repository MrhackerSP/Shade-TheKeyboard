package com.noircipher.shade

import kotlin.math.abs
import kotlin.math.min

/**
 * Touch geometry — tracks where the finger actually landed on each key
 * and uses that in the autocorrection calculation.
 *
 * If you hit the right edge of "a" (near "s"), the correction to "s"
 * becomes nearly free. If you hit dead center of "a", corrections away
 * from "a" become expensive.
 *
 * Ported from Expressive Keyboard's touch geometry system.
 */
object TouchGeometry {

    /** Stddev of finger scatter around key center, in key-width units. */
    var sigmaX = 0.33f
    var sigmaY = 0.25f

    /**
     * A single touch event on a key: offset from center in key-width units.
     * dx > 0 = right, dy > 0 = down.
     */
    data class KeyTouch(val dx: Float, val dy: Float)

    /**
     * −ln P(touch | target) − −ln P(touch | typed), up to a constant.
     * Lower = more likely the touch was meant for target.
     * Returns Float.MAX_VALUE if positions unknown.
     */
    fun touchCost(
        typed: Char,
        target: Char,
        touch: KeyTouch?,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): Float {
        if (touch == null) return Float.MAX_VALUE
        val p = keyPositions[typed] ?: return Float.MAX_VALUE
        val q = keyPositions[target] ?: return Float.MAX_VALUE
        val dx = (p.second + touch.dx - q.second) / sigmaX
        val dy = (p.first + touch.dy - q.first) / sigmaY
        return 0.5f * (dx * dx + dy * dy)
    }

    /**
     * Substitution cost: how expensive is it to replace [a] with [b]?
     * If we know the touch position, the geometry decides.
     * If not, falls back to QWERTY adjacency.
     */
    fun subCost(
        a: Char,
        b: Char,
        touch: KeyTouch?,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): Float {
        if (a == b) return 0f
        if (touch != null && keyPositions.containsKey(a) && keyPositions.containsKey(b)) {
            val spatial = touchCost(a, b, touch, keyPositions) - touchCost(a, a, touch, keyPositions)
            return spatial.coerceIn(0.25f, 9.4f)
        }
        // Fallback: QWERTY adjacency
        val pa = keyPositions[a] ?: return 9.4f
        val pb = keyPositions[b] ?: return 9.4f
        val adjacent = abs(pa.first - pb.first) <= 1f && abs(pa.second - pb.second) <= 1.01f
        return if (adjacent) 2.8f else 9.4f
    }

    /**
     * Keys that the finger might have meant instead of [a], based on touch position.
     */
    fun plausibleKeys(
        a: Char,
        touch: KeyTouch?,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): List<Char> {
        val out = ArrayList<Char>(6)
        val pa = keyPositions[a] ?: return out
        for (c in keyPositions.keys) {
            if (c == a || !c.isLetter()) continue
            val pc = keyPositions[c] ?: continue
            val adjacent = abs(pa.first - pc.first) <= 1f && abs(pa.second - pc.second) <= 1.01f
            if (adjacent) {
                val cost = if (touch != null) {
                    touchCost(a, c, touch, keyPositions) - touchCost(a, a, touch, keyPositions)
                } else Float.MAX_VALUE
                if (touch == null || cost < 5f) out.add(c)
            }
        }
        return out
    }

    /**
     * Compute the touch offset (in key-width units) from the center of [key]
     * at coordinate (x, y). Returns null if the key has no measurable area.
     * Used to feed [KeyTouch] into the spatial substitution cost.
     */
    fun computeTouchOffset(key: Key, x: Float, y: Float): KeyTouch? {
        val w = key.right - key.left
        val h = key.bottom - key.top
        if (w <= 0f || h <= 0f) return null
        val dx = (x - key.centerX) / w
        val dy = (y - key.centerY) / h
        return KeyTouch(dx, dy)
    }
}
