package com.noircipher.shade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure color math for Shade's settings — no android.* imports so the whole
 * object runs (and is unit-tested) on the plain JVM.
 *
 * Colors are ARGB ints, opaque unless a 8-digit hex with alpha is parsed.
 * HSV follows the standard Android convention: h in [0,360), s and v in [0,1].
 */
object ColorMath {

    // ── Hex strings ────────────────────────────────────────────────────────

    /**
     * Normalizes user hex input to a canonical "#RRGGBB" string.
     * Accepts "#abc", "abc", "#aabbcc", "aabbcc" and an 8-digit "#AARRGGBB"
     * (alpha is preserved for parity with Color.parseColor, which the theme
     * consumer uses). Returns null when the input is not valid hex.
     */
    fun normalizeHex(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("#")?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val expanded = when (s.length) {
            3 -> s.map { c -> "$c$c" }.joinToString("")          // #abc → #aabbcc
            6 -> s
            8 -> s                                                // keep #AARRGGBB
            else -> return null
        }
        if (expanded.any { it !in "0123456789abcdef" }) return null
        return "#$expanded"
    }

    fun isValidHex(raw: String?): Boolean = normalizeHex(raw) != null

    /** Parses a hex string (with or without '#') into an ARGB int; null when invalid. */
    fun parseHex(raw: String?): Int? {
        val norm = normalizeHex(raw) ?: return null
        val digits = norm.removePrefix("#")
        val argb = when (digits.length) {
            6 -> 0xFF000000.toInt() or digits.toLong(16).toInt()
            8 -> digits.toLong(16).toInt()
            else -> return null
        }
        return argb
    }

    /** Formats an ARGB int as an opaque "#RRGGBB" string (alpha dropped). */
    fun formatHex(argb: Int): String =
        String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and argb)

    // ── HSV conversions (standard formulas, matches android.graphics.Color) ─

    /** RGB → HSV. Returns floatArrayOf(hue, sat, val) with h in [0,360), s/v in [0,1]. */
    fun rgbToHsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val h = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val hue = (if (h < 0f) h + 360f else h) % 360f
        val s = if (maxC == 0f) 0f else delta / maxC
        return floatArrayOf(hue, s, maxC)
    }

    /** HSV → opaque ARGB int. h in [0,360), s/v in [0,1]. */
    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hh = (h % 360f + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs(hh / 60f % 2f - 1f))
        val m = vv - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return 0xFF000000.toInt() or
            ((r1 + m).times(255).roundToInt() shl 16) or
            ((g1 + m).times(255).roundToInt() shl 8) or
            (b1 + m).times(255).roundToInt()
    }

    // ── Perceptual helpers ─────────────────────────────────────────────────

    /**
     * WCAG 2.x relative luminance of the RGB channels (alpha ignored),
     * matching androidx ColorUtils.calculateLuminance().
     */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(argb shr 16 and 0xFF)
        val g = channel(argb shr 8 and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Black text on light backgrounds, white text on dark ones. */
    fun contrastTextColor(bg: Int): Int =
        if (luminance(bg) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** True when the two colors are perceptually the same opaque color. */
    fun sameColor(a: Int, b: Int): Boolean =
        (a and 0xFFFFFF) == (b and 0xFFFFFF)
}
