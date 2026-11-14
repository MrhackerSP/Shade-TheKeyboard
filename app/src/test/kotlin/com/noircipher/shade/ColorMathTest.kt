package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the color math used by the settings color picker.
 * Expected values were computed independently (Python colorsys + WCAG
 * formulas), not derived from the implementation.
 */
class ColorMathTest {

    // ── Hex normalization ────────────────────────────────────────────────

    @Test
    fun normalizeHex_acceptsCommonForms() {
        assertEquals("#aabbcc", ColorMath.normalizeHex("#aabbcc"))
        assertEquals("#aabbcc", ColorMath.normalizeHex("aabbcc"))
        assertEquals("#aabbcc", ColorMath.normalizeHex("  AABBCC  "))
        assertEquals("#aabbcc", ColorMath.normalizeHex("#ABC"))
        assertEquals("#aabbcc", ColorMath.normalizeHex("abc"))
        assertEquals("#11223344", ColorMath.normalizeHex("#11223344"))
    }

    @Test
    fun normalizeHex_rejectsGarbage() {
        assertNull(ColorMath.normalizeHex(null))
        assertNull(ColorMath.normalizeHex(""))
        assertNull(ColorMath.normalizeHex("#"))
        assertNull(ColorMath.normalizeHex("red"))
        assertNull(ColorMath.normalizeHex("12345"))
        assertNull(ColorMath.normalizeHex("#12345"))
        assertNull(ColorMath.normalizeHex("#zzzzzz"))
        assertNull(ColorMath.normalizeHex("#11223g"))
    }

    @Test
    fun isValidHex_matchesNormalize() {
        assertTrue(ColorMath.isValidHex("#7C4DFF"))
        assertTrue(ColorMath.isValidHex("7c4dff"))
        assertFalse(ColorMath.isValidHex("red"))
        assertFalse(ColorMath.isValidHex(""))
        assertFalse(ColorMath.isValidHex(null))
    }

    // ── Parsing / formatting ─────────────────────────────────────────────

    @Test
    fun parseHex_knownColors() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.parseHex("#ff0000"))
        assertEquals(0xFFFF0000.toInt(), ColorMath.parseHex("FF0000"))
        assertEquals(0xFFAABBCC.toInt(), ColorMath.parseHex("#abc"))
        assertEquals(0xFF7C4DFF.toInt(), ColorMath.parseHex("#7C4DFF"))
        // 8-digit form keeps alpha (parity with Color.parseColor).
        assertEquals(0x80112233.toInt(), ColorMath.parseHex("#80112233"))
    }

    @Test
    fun parseHex_invalidReturnsNull() {
        assertNull(ColorMath.parseHex(null))
        assertNull(ColorMath.parseHex(""))
        assertNull(ColorMath.parseHex("nope"))
    }

    @Test
    fun formatHex_dropsAlphaAndUppercases() {
        assertEquals("#7C4DFF", ColorMath.formatHex(0xFF7C4DFF.toInt()))
        assertEquals("#112233", ColorMath.formatHex(0x80112233.toInt()))
        assertEquals("#000000", ColorMath.formatHex(0xFF000000.toInt()))
        assertEquals("#FFFFFF", ColorMath.formatHex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun parseFormat_roundTrip() {
        for (raw in listOf("#123456", "#ABCDEF", "#000000", "#FFFFFF", "#808080", "#7C4DFF")) {
            assertEquals(raw, ColorMath.formatHex(ColorMath.parseHex(raw)!!))
        }
    }

    // ── RGB ↔ HSV ────────────────────────────────────────────────────────

    @Test
    fun rgbToHsv_primaries() {
        assertArrayClose(ColorMath.rgbToHsv(0xFFFF0000.toInt()), floatArrayOf(0f, 1f, 1f), 1e-4f)
        assertArrayClose(ColorMath.rgbToHsv(0xFF00FF00.toInt()), floatArrayOf(120f, 1f, 1f), 1e-4f)
        assertArrayClose(ColorMath.rgbToHsv(0xFF0000FF.toInt()), floatArrayOf(240f, 1f, 1f), 1e-4f)
        assertArrayClose(ColorMath.rgbToHsv(0xFFFFFFFF.toInt()), floatArrayOf(0f, 0f, 1f), 1e-4f)
        assertArrayClose(ColorMath.rgbToHsv(0xFF000000.toInt()), floatArrayOf(0f, 0f, 0f), 1e-4f)
    }

    @Test
    fun rgbToHsv_knownMixes() {
        // #7C4DFF → hue 255.84°, sat 0.698, val 1.0 (independent computation).
        val violet = ColorMath.rgbToHsv(0xFF7C4DFF.toInt())
        assertEquals(255.8427f, violet[0], 0.01f)
        assertEquals(0.698f, violet[1], 0.001f)
        assertEquals(1f, violet[2], 0.001f)

        // #8AB4F8 → hue 217.09°, sat 0.4435, val 0.9725.
        val blue = ColorMath.rgbToHsv(0xFF8AB4F8.toInt())
        assertEquals(217.0909f, blue[0], 0.01f)
        assertEquals(0.4435f, blue[1], 0.001f)
        assertEquals(0.9725f, blue[2], 0.001f)
    }

    @Test
    fun hsvToRgb_knownValues() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToRgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorMath.hsvToRgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsvToRgb(240f, 1f, 1f))
        assertEquals(0xFFFFFF00.toInt(), ColorMath.hsvToRgb(60f, 1f, 1f))
        // Independent Python-colorsys results for two arbitrary points.
        assertEquals(0xFF88C0F7.toInt(), ColorMath.hsvToRgb(210f, 0.45f, 0.97f))
        assertEquals(0xFF9D4DFF.toInt(), ColorMath.hsvToRgb(267f, 0.7f, 1f))
    }

    @Test
    fun hsvToRgb_wrapsHueAndClamps() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToRgb(360f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToRgb(-360f, 1f, 1f))
        assertEquals(0xFF000000.toInt(), ColorMath.hsvToRgb(123f, 1f, -5f))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.hsvToRgb(123f, -1f, 2f))
    }

    @Test
    fun hsvRoundTrip_preservesColor() {
        // rgb → hsv → rgb must reproduce the color (±1 per channel for rounding).
        val samples = intArrayOf(
            0xFF7C4DFF.toInt(), 0xFF8AB4F8.toInt(), 0xFF34A853.toInt(),
            0xFFF9AB2B.toInt(), 0xFFF28B82.toInt(), 0xFF1C1C1E.toInt(),
            0xFF3C3C40.toInt(), 0xFF2C2C2E.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFF9E9E93.toInt()
        )
        for (argb in samples) {
            val hsv = ColorMath.rgbToHsv(argb)
            val back = ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2])
            val dr = Math.abs((back shr 16 and 0xFF) - (argb shr 16 and 0xFF))
            val dg = Math.abs((back shr 8 and 0xFF) - (argb shr 8 and 0xFF))
            val db = Math.abs((back and 0xFF) - (argb and 0xFF))
            assertTrue("round trip failed for ${ColorMath.formatHex(argb)}: got ${ColorMath.formatHex(back)}",
                dr <= 1 && dg <= 1 && db <= 1)
        }
    }

    // ── Luminance / contrast ─────────────────────────────────────────────

    @Test
    fun luminance_primariesAndGrays() {
        assertEquals(0.0, ColorMath.luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, ColorMath.luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.2126, ColorMath.luminance(0xFFFF0000.toInt()), 1e-4)
        assertEquals(0.7152, ColorMath.luminance(0xFF00FF00.toInt()), 1e-4)
        assertEquals(0.0722, ColorMath.luminance(0xFF0000FF.toInt()), 1e-4)
        assertEquals(0.2159, ColorMath.luminance(0xFF808080.toInt()), 1e-4)
        assertEquals(0.3419, ColorMath.luminance(0xFF9E9E9E.toInt()), 1e-4)
    }

    @Test
    fun contrastTextColor_selectsReadableColor() {
        assertEquals(0xFF000000.toInt(), ColorMath.contrastTextColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastTextColor(0xFF000000.toInt()))
        // #FFB300 luminance 0.535 → black text.
        assertEquals(0xFF000000.toInt(), ColorMath.contrastTextColor(0xFFFFB300.toInt()))
        // #7C4DFF luminance 0.168 → white text.
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastTextColor(0xFF7C4DFF.toInt()))
        // #8AB4F8 luminance 0.448 (just below threshold) → white text.
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastTextColor(0xFF8AB4F8.toInt()))
        // #9E9E9E luminance 0.342 → white text.
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastTextColor(0xFF9E9E9E.toInt()))
    }

    @Test
    fun sameColor_ignoresAlpha() {
        assertTrue(ColorMath.sameColor(0xFF123456.toInt(), 0x80123456.toInt()))
        assertFalse(ColorMath.sameColor(0xFF123456.toInt(), 0xFF123457.toInt()))
    }

    private fun assertArrayClose(actual: FloatArray, expected: FloatArray, tol: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], tol)
    }
}
