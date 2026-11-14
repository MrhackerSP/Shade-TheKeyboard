package com.noircipher.shade

import kotlin.math.abs
import kotlin.math.min

/**
 * Material You palette builder — derives a keyboard color scheme from the
 * wallpaper's seed colors, in the spirit of Android 12+ dynamic color.
 *
 * Pure Kotlin (no android.* imports): input seeds are ARGB ints, output is a
 * [KbTheme]-shaped palette built with [ColorMath] HSV tone mapping, so the
 * whole thing runs and is unit-tested on the plain JVM.
 *
 * Tonal mapping (approximating Material's neutral/accent tone stops):
 *  - Surfaces use the primary seed's HUE with heavily capped saturation
 *    (Monet "neutral" families are near-gray).
 *  - Accent uses the tertiary seed when the wallpaper provides one, falling
 *    back to a +60 deg hue shift of the primary seed (Monet behavior).
 *  - Dark scheme: background near-black with a whisper of the wallpaper hue.
 *  - Light scheme: near-white background, dark text, mid-tone accent.
 */
object MaterialYouPalette {

    /** Extracted wallpaper seeds. Nullable because wallpapers may expose fewer colors. */
    data class Seeds(val primary: Int, val secondary: Int?, val tertiary: Int?)

    fun build(primary: Int, secondary: Int?, tertiary: Int?, dark: Boolean): KbTheme {
        val primaryHsv = ColorMath.rgbToHsv(primary)
        val hue = primaryHsv[0]
        // Secondary seed hue when available; else a small -15 deg drift (Monet-ish).
        val secondaryHue = secondary?.let { ColorMath.rgbToHsv(it)[0] }
            ?: (hue + 345f) % 360f
        // Tertiary seed hue when available; else a +60 deg drift.
        val tertiaryHue = tertiary?.let { ColorMath.rgbToHsv(it)[0] }
            ?: (hue + 60f) % 360f

        return if (dark) {
            KbTheme(
                bg = tone(hue, 0.065f, NEUTRAL_SAT),
                key = tone(hue, 0.18f, NEUTRAL_SAT),
                keyFn = tone(secondaryHue, 0.13f, NEUTRAL_SAT),
                pressed = tone(hue, 0.32f, SOFT_SAT),
                text = tone(hue, 0.96f, TEXT_SAT),
                hint = tone(hue, 0.62f, NEUTRAL_SAT),
                accent = tone(tertiaryHue, 0.72f, ACCENT_SAT),
                accentText = tone(tertiaryHue, 0.12f, ACCENT_SAT),
                popupBg = tone(hue, 0.24f, NEUTRAL_SAT)
            )
        } else {
            KbTheme(
                bg = tone(hue, 0.97f, NEUTRAL_SAT),
                key = tone(hue, 1.0f, NEUTRAL_SAT),
                keyFn = tone(secondaryHue, 0.90f, NEUTRAL_SAT),
                pressed = tone(hue, 0.80f, SOFT_SAT),
                text = tone(hue, 0.13f, TEXT_SAT),
                hint = tone(hue, 0.45f, NEUTRAL_SAT),
                accent = tone(tertiaryHue, 0.45f, ACCENT_SAT),
                accentText = tone(tertiaryHue, 0.98f, NEUTRAL_SAT),
                popupBg = tone(hue, 1.0f, NEUTRAL_SAT)
            )
        }
    }

    /** Builds from nullable wallpaper colors; null primary falls back to [FALLBACK_SEED]. */
    fun build(seeds: Seeds?, dark: Boolean): KbTheme {
        val primary = seeds?.primary ?: FALLBACK_SEED
        return build(primary, seeds?.secondary, seeds?.tertiary, dark)
    }

    /** Single-color convenience: derive secondary/tertiary as hue drifts. */
    fun buildFromSingle(seed: Int, dark: Boolean): KbTheme =
        build(seed, null, null, dark)

    /**
     * Maps a seed color onto a fixed brightness/saturation "tone": keeps the
     * wallpaper's hue but re-anchors value and caps saturation, which is what
     * makes the result look like a palette instead of a recolor.
     */
    private fun tone(seedHue: Float, value: Float, maxSat: Float): Int =
        ColorMath.hsvToRgb(seedHue, maxSat, value)

    /** Sanity clamp for wallpaper colors that arrive with alpha or garbage channels. */
    fun sanitize(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a == 0) return FALLBACK_SEED
        return (argb or 0xFF000000.toInt())
    }

    /** Rejects absurd seeds (NaN-ish brightness, chroma > 1) defensively. */
    fun isValidSeed(argb: Int): Boolean {
        if ((argb ushr 24) and 0xFF == 0) return false
        val hsv = ColorMath.rgbToHsv(argb)
        if (!hsv[0].isFinite() || !hsv[1].isFinite() || !hsv[2].isFinite()) return false
        if (abs(hsv[2]) > 1.001f) return false
        return true
    }

    /** Neutral surface saturation cap (Monet neutral families sit at 3-7%).
     *  Kept tight: at very low brightness, HSV saturation readouts are
     *  rounding-sensitive, so neutrality is enforced at the source. */
    private const val NEUTRAL_SAT = 0.06f
    /** Key labels are effectively white/black with a whisper of hue: chroma
     *  on text costs legibility without adding any Material You character. */
    private const val TEXT_SAT = 0.02f
    /** Pressed/highlight surfaces get a bit more chroma. */
    private const val SOFT_SAT = 0.22f
    /** Accent keeps meaningful chroma but is reined in from neon wallpapers. */
    private const val ACCENT_SAT = 0.62f
    /** Used when the wallpaper exposes no colors at all (e.g. live wallpapers). */
    private const val FALLBACK_SEED = 0xFF7C8A9B.toInt()

    /** Min neutral contrast guard: text must read on bg in both modes. */
    fun contrastSafe(theme: KbTheme): Boolean {
        val lumBg = ColorMath.luminance(theme.bg)
        val lumText = ColorMath.luminance(theme.text)
        val ratio = if (lumBg > lumText) {
            (lumBg + 0.05) / (lumText + 0.05)
        } else {
            (lumText + 0.05) / (lumBg + 0.05)
        }
        return ratio >= 4.0
    }

    /** Utility clamp kept public for tests. */
    fun clamp01(v: Float): Float = min(1f, kotlin.math.max(0f, v))
}
