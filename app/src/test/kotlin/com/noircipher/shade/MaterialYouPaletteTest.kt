package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the Material You palette builder: tone mapping keeps
 * the wallpaper hue where chroma is meaningful, surfaces stay near-neutral,
 * both modes keep readable contrast, and degenerate seeds fall back safely.
 *
 * Note on tolerances: hue is asserted only for mid/high-chroma tones (the
 * accent). On near-black surfaces (value ~6%) a 1/255 rounding step in a
 * channel swings the recomputed hue by tens of degrees, so those assertions
 * check brightness/saturation and distinctness instead — that is what the
 * palette actually guarantees there.
 */
class MaterialYouPaletteTest {

    private val VIOLET = 0xFF7C4DFF.toInt()

    @Test fun darkSchemeIsDarkNeutralTextReadable() {
        val theme = MaterialYouPalette.build(VIOLET, null, null, dark = true)
        val bgHsv = ColorMath.rgbToHsv(theme.bg)
        // Dark background is really dark.
        assertTrue("dark bg too bright: $bgHsv", bgHsv[2] < 0.15f)
        // Neutrality via channel spread: HSV saturation readouts on near-black
        // are rounding-sensitive (2/255 of delta reads as ~12% saturation),
        // so compare the actual channels instead.
        val r = (theme.bg shr 16) and 0xFF
        val g = (theme.bg shr 8) and 0xFF
        val b = theme.bg and 0xFF
        assertTrue("surface not neutral: r=$r g=$g b=$b", maxOf(r, g, b) - minOf(r, g, b) <= 6)
        // Text is near-white.
        assertTrue(ColorMath.luminance(theme.text) > 0.85)
        assertTrue(MaterialYouPalette.contrastSafe(theme))
    }

    @Test fun lightSchemeIsReadable() {
        val theme = MaterialYouPalette.build(0xFFFFB300.toInt(), null, null, dark = false)
        assertTrue("light bg too dark: ${theme.bg}", ColorMath.luminance(theme.bg) > 0.85)
        assertTrue("light text too bright: ${theme.text}", ColorMath.luminance(theme.text) < 0.2)
        assertTrue(MaterialYouPalette.contrastSafe(theme))
    }

    @Test fun accentKeepsMeaningfulChroma() {
        val theme = MaterialYouPalette.build(0xFF2E7D32.toInt(), null, null, dark = true)
        val accentHsv = ColorMath.rgbToHsv(theme.accent)
        assertTrue("accent desaturated to gray: $accentHsv", accentHsv[1] >= 0.3f)
        assertTrue("accent oversaturated: $accentHsv", accentHsv[1] <= 0.7f)
    }

    @Test fun accentPreservesWallpaperHue() {
        // The accent is the one tone with enough chroma for an exact hue roundtrip.
        val theme = MaterialYouPalette.build(VIOLET, null, null, dark = true)
        val seedHsv = ColorMath.rgbToHsv(VIOLET)
        // No tertiary seed -> tertiary hue = primary + 60.
        val expected = (seedHsv[0] + 60f) % 360f
        assertEquals(expected, ColorMath.rgbToHsv(theme.accent)[0], 2.0f)
    }

    @Test fun tertiarySeedDrivesAccentHue() {
        val tertiary = 0xFFE91E63.toInt()
        val theme = MaterialYouPalette.build(VIOLET, null, tertiary, dark = true)
        val tertiaryHue = ColorMath.rgbToHsv(tertiary)[0]
        assertEquals(tertiaryHue, ColorMath.rgbToHsv(theme.accent)[0], 2.0f)
    }

    @Test fun secondarySeedChangesFunctionKeys() {
        val withSecondary = MaterialYouPalette.build(VIOLET, 0xFF00897B.toInt(), null, dark = true)
        val withoutSecondary = MaterialYouPalette.build(VIOLET, null, null, dark = true)
        assertNotEquals(withSecondary.keyFn, withoutSecondary.keyFn)
    }

    @Test fun wallpaperHueChangesAccent() {
        val red = MaterialYouPalette.build(0xFFD32F2F.toInt(), null, null, dark = true)
        val blue = MaterialYouPalette.build(0xFF1976D2.toInt(), null, null, dark = true)
        assertNotEquals("different wallpapers must give different accents", red.accent, blue.accent)
    }

    @Test fun nullSeedsFallBackToUsableTheme() {
        val theme = MaterialYouPalette.build(null, dark = true)
        assertTrue(MaterialYouPalette.contrastSafe(theme))
    }

    @Test fun bothModesDiffer() {
        val dark = MaterialYouPalette.buildFromSingle(0xFF3F51B5.toInt(), dark = true)
        val light = MaterialYouPalette.buildFromSingle(0xFF3F51B5.toInt(), dark = false)
        assertTrue(dark.bg != light.bg)
        assertTrue(dark.text != light.text)
    }

    @Test fun contrastGuardHoldsForExtremeWallpapers() {
        // Pure saturated primaries in both modes must still produce readable schemes.
        for (seed in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())) {
            val dark = MaterialYouPalette.buildFromSingle(seed, dark = true)
            val light = MaterialYouPalette.buildFromSingle(seed, dark = false)
            assertTrue("dark unreadable for $seed", MaterialYouPalette.contrastSafe(dark))
            assertTrue("light unreadable for $seed", MaterialYouPalette.contrastSafe(light))
        }
    }

    @Test fun seedValidation() {
        assertTrue(MaterialYouPalette.isValidSeed(VIOLET))
        assertFalse(MaterialYouPalette.isValidSeed(0x00123456))  // transparent
    }

    @Test fun sanitizeForcesOpaque() {
        assertEquals(0xFFFF0000.toInt(), MaterialYouPalette.sanitize(0x80FF0000.toInt()))
        // Fully transparent input falls back to the built-in neutral seed.
        assertEquals(MaterialYouPalette.sanitize(0), MaterialYouPalette.sanitize(0x00FF0000.toInt()))
        assertTrue(MaterialYouPalette.isValidSeed(MaterialYouPalette.sanitize(0)))
    }
}
