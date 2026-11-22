package com.shade.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the background-image math ported from
 * Expressive-Keyboard v11.2.6 (KeyboardPhoto.drawKeyboardPhoto,
 * rememberBackgroundBitmap decode cap, bg_dim / key_opacity /
 * bg_transparency knobs).
 *
 * Frame vectors were computed independently in Python following the
 * Expressive DrawScope source, not by running the implementation.
 */
class BackgroundMathTest {

    // ── Decode sampling ─────────────────────────────────────────────────────

    @Test
    fun decodeSampleSize_mirrorsExpressiveCap() {
        // while (longest / sample > cap) sample *= 2
        assertEquals(1, BackgroundMath.decodeSampleSize(1080, 800))
        assertEquals(1, BackgroundMath.decodeSampleSize(2048, 1000))  // 2048 not > 2048
        assertEquals(2, BackgroundMath.decodeSampleSize(4000, 3000))
        assertEquals(8, BackgroundMath.decodeSampleSize(8200, 1000))  // 8200/4=2050>2048 → 8
        assertEquals(8, BackgroundMath.decodeSampleSize(16384, 2048))  // 16384/8=2048 stops the loop
        assertEquals(1, BackgroundMath.decodeSampleSize(0, 0))        // degenerate → no sampling
        assertEquals(2, BackgroundMath.decodeSampleSize(5000, 5000, cap = 2500))
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    fun frame_cover_fillsAndCropsCentered() {
        // 4:3 image into 27:20 view: scaleX 0.27 wins → 1080x810, 5px vertical crop.
        val f = BackgroundMath.frame(BackgroundMath.Fit.COVER, 4000, 3000, 1080, 800)
        assertEquals(0f, f[0], 1e-3f)
        assertEquals(-5f, f[1], 1e-3f)
        assertEquals(1080f, f[2], 1e-3f)
        assertEquals(810f, f[3], 1e-3f)
    }

    @Test
    fun frame_fit_letterboxes() {
        val f = BackgroundMath.frame(BackgroundMath.Fit.FIT, 4000, 3000, 1080, 800)
        assertEquals(6.6667f, f[0], 1e-3f)
        assertEquals(0f, f[1], 1e-3f)
        assertEquals(1066.6667f, f[2], 1e-3f)
        assertEquals(800f, f[3], 1e-3f)
    }

    @Test
    fun frame_cover_tallImage_cropsSidesNotTop() {
        val f = BackgroundMath.frame(BackgroundMath.Fit.COVER, 1000, 4000, 1080, 800)
        assertEquals(0f, f[0], 1e-3f)          // width fills exactly
        assertEquals(-1760f, f[1], 1e-3f)      // height overflows, centered
        assertEquals(1080f, f[2], 1e-3f)
        assertEquals(4320f, f[3], 1e-3f)
    }

    @Test
    fun frame_stretch_ignoresZoomButHonorsPan() {
        // Expressive: STRETCH draws at view size × zoom (per drawKeyboardPhoto,
        // width/height factors multiply the stretched size too — zoom=2 doubles).
        val f = BackgroundMath.frame(
            BackgroundMath.Fit.STRETCH, 4000, 3000, 1080, 800, zoom = 2f
        )
        assertEquals(-540f, f[0], 1e-3f)
        assertEquals(-400f, f[1], 1e-3f)
        assertEquals(2160f, f[2], 1e-3f)
        assertEquals(1600f, f[3], 1e-3f)
    }

    @Test
    fun frame_zoomAndPan_followExpressiveFormula() {
        // left = (viewW - width + viewW * panX) / 2
        val zoomed = BackgroundMath.frame(BackgroundMath.Fit.COVER, 4000, 3000, 1080, 800, zoom = 2f)
        assertEquals(-540f, zoomed[0], 1e-3f)
        assertEquals(-410f, zoomed[1], 1e-3f)
        assertEquals(2160f, zoomed[2], 1e-3f)
        assertEquals(1620f, zoomed[3], 1e-3f)

        val panned = BackgroundMath.frame(
            BackgroundMath.Fit.COVER, 4000, 3000, 1080, 800, zoom = 2f, panX = 1f
        )
        assertEquals(0f, panned[0], 1e-3f)     // (1080-2160+1080)/2
        assertEquals(-410f, panned[1], 1e-3f)

        val framed = BackgroundMath.frame(
            BackgroundMath.Fit.COVER, 4000, 3000, 1080, 800,
            widthF = 0.5f, heightF = 1.5f
        )
        assertEquals(270f, framed[0], 1e-3f)
        assertEquals(-207.5f, framed[1], 1e-3f)
        assertEquals(540f, framed[2], 1e-3f)
        assertEquals(1215f, framed[3], 1e-3f)
    }

    @Test
    fun frame_fitSquareImage_centers() {
        val f = BackgroundMath.frame(BackgroundMath.Fit.FIT, 1000, 1000, 1080, 800)
        assertEquals(140f, f[0], 1e-3f)
        assertEquals(0f, f[1], 1e-3f)
        assertEquals(800f, f[2], 1e-3f)
        assertEquals(800f, f[3], 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frame_rejectsDegenerateDimensions() {
        BackgroundMath.frame(BackgroundMath.Fit.COVER, 0, 100, 100, 100)
    }

    // ── Alpha composition ───────────────────────────────────────────────────

    @Test
    fun dimAlpha_matchesExpressiveRange() {
        assertEquals(0, BackgroundMath.dimAlpha(0))
        assertEquals(89, BackgroundMath.dimAlpha(35))   // Expressive default bg_dim=0.35
        assertEquals(229, BackgroundMath.dimAlpha(90))  // max 0.9
        assertEquals(0, BackgroundMath.dimAlpha(-5))    // clamped
        assertEquals(229, BackgroundMath.dimAlpha(120)) // clamped
    }

    @Test
    fun keyAlpha_reproducesOldSofteningAt80Percent() {
        assertEquals(255, BackgroundMath.keyAlpha(100))
        assertEquals(204, BackgroundMath.keyAlpha(80))  // the old hardcoded 0xCC
        assertEquals(51, BackgroundMath.keyAlpha(20))   // Expressive floor 0.2
        assertEquals(51, BackgroundMath.keyAlpha(5))    // clamped low
        assertEquals(255, BackgroundMath.keyAlpha(150)) // clamped high
    }

    @Test
    fun bgArgb_reducesAlphaByTransparency() {
        assertEquals(0xFF1C1C1E.toInt(), BackgroundMath.bgArgb(0xFF1C1C1E.toInt(), 0))
        assertEquals(0x991C1C1E.toInt(), BackgroundMath.bgArgb(0xFF1C1C1E.toInt(), 40))
        assertEquals(0x001C1C1E.toInt(), BackgroundMath.bgArgb(0xFF1C1C1E.toInt(), 100))
        assertEquals(0xFF1C1C1E.toInt(), BackgroundMath.bgArgb(0x801C1C1E.toInt(), 0)) // opaque input normalized
        assertEquals(0x001C1C1E.toInt(), BackgroundMath.bgArgb(0xFF1C1C1E.toInt(), 150)) // clamped
    }

    // ── Blur ────────────────────────────────────────────────────────────────

    private fun solid(w: Int, h: Int, c: Int): IntArray = IntArray(w * h) { c }

    @Test
    fun boxBlur_radiusZero_returnsCopy() {
        val src = solid(3, 3, 0xFF102030.toInt())
        val out = BackgroundMath.boxBlurPx(src, 3, 3, 0)
        assertTrue(out.contentEquals(src))
        // Mutation isolation: the input array is untouched.
        out[0] = 0
        assertEquals(0xFF102030.toInt(), src[0])
    }

    @Test
    fun boxBlur_uniformImage_staysUniform() {
        val src = solid(7, 5, 0xFF64C832.toInt())
        val out = BackgroundMath.boxBlurPx(src, 7, 5, 2)
        for (p in out) assertEquals(0xFF64C832.toInt(), p)
    }

    @Test
    fun boxBlur_impulse_spreadsToNeighbors() {
        // Single white impulse at the center of a black 5x5, radius 1.
        // Horizontal pass spreads the impulse into a 3-wide bar of 255/3=85;
        // the vertical pass averages that bar over 3 rows → 28 at the center
        // and its 8-neighbors. Corner (0,0) never sees it: its clamped
        // window only covers black rows.
        val src = solid(5, 5, 0xFF000000.toInt())
        src[12] = 0xFFFFFFFF.toInt()
        val out = BackgroundMath.boxBlurPx(src, 5, 5, 1)
        assertEquals(28, out[12] and 0xFF)          // center
        assertEquals(28, out[7] and 0xFF)           // neighbor above
        assertEquals(28, out[6] and 0xFF)           // diagonal
        assertEquals(0, out[0] and 0xFF)            // corner untouched
    }

    @Test
    fun boxBlur_preservesSizeAndAlpha() {
        val src = solid(4, 6, 0x804080C0.toInt())
        val out = BackgroundMath.boxBlurPx(src, 4, 6, 2)
        assertEquals(src.size, out.size)
        for (p in out) assertEquals(0x80, p ushr 24) // alpha channel is blurred too, uniform stays
    }

    @Test(expected = IllegalArgumentException::class)
    fun boxBlur_rejectsSizeMismatch() {
        BackgroundMath.boxBlurPx(IntArray(4), 3, 3, 1)
    }
}
