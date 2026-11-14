package com.noircipher.shade

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils

class KbTheme(
    val bg: Int,
    val key: Int,
    val keyFn: Int,
    val pressed: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val accentText: Int,
    val popupBg: Int
)

object Themes {
    val LIGHT = KbTheme(
        bg = 0xFFE9ECF1.toInt(),
        key = 0xFFFFFFFF.toInt(),
        keyFn = 0xFFCDD2DA.toInt(),
        pressed = 0xFFB7C3DA.toInt(),
        text = 0xFF1B1B1F.toInt(),
        hint = 0xFF6B6F76.toInt(),
        accent = 0xFF3B6FD4.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popupBg = 0xFFFFFFFF.toInt()
    )

    /**
     * Dark theme tuned to match modern keyboard's contrast on a modern Pixel:
     * keys are noticeably lighter than the keyboard background so they
     * read as distinct surfaces, matching the reference screenshot.
     */
    val DARK = KbTheme(
        bg = 0xFF1C1C1E.toInt(),
        key = 0xFF3C3C40.toInt(),
        keyFn = 0xFF2C2C2E.toInt(),
        pressed = 0xFF5A5A60.toInt(),
        text = 0xFFFFFFFF.toInt(),
        hint = 0xFF8E8E93.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        accentText = 0xFF0B1B3A.toInt(),
        popupBg = 0xFF48484A.toInt()
    )

    val AMOLED = KbTheme(
        bg = 0xFF000000.toInt(),
        key = 0xFF1C1C1E.toInt(),
        keyFn = 0xFF111112.toInt(),
        pressed = 0xFF3A3A3F.toInt(),
        text = 0xFFEDEDED.toInt(),
        hint = 0xFF8E9096.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        accentText = 0xFF0B1B3A.toInt(),
        popupBg = 0xFF2A2A2D.toInt()
    )

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** True for theme values that follow the system wallpaper (Material You). */
    fun usesWallpaper(prefs: SharedPreferences): Boolean {
        val t = prefs.getString("theme", "system") ?: "system"
        return (t == "material" || t == "dynamic") && Build.VERSION.SDK_INT >= 31
    }

    fun resolve(ctx: Context, prefs: SharedPreferences): KbTheme {
        val night = isNight(ctx)
        val base = if (night) DARK else LIGHT
        return when (prefs.getString("theme", "system")) {
            "light" -> LIGHT
            "dark" -> DARK
            "amoled" -> AMOLED
            // "dynamic" is the legacy pref value for wallpaper theming; "material"
            // is the catalog label. Both resolve through the same Material You path.
            "dynamic" -> if (Build.VERSION.SDK_INT >= 31) materialYou(ctx, night) else base
            "material" -> if (Build.VERSION.SDK_INT >= 31) materialYou(ctx, night) else base
            "custom" -> custom(prefs, base)
            else -> base
        }
    }

    private fun parse(s: String?, def: Int): Int {
        if (s.isNullOrBlank()) return def
        val t = s.trim()
        return try { Color.parseColor(if (t.startsWith("#")) t else "#$t") } catch (t: Exception) { ShadeLog.w("Theme", "theme color parse failed", t, once = true); def }
    }

    private fun custom(p: SharedPreferences, base: KbTheme): KbTheme {
        val bg = parse(p.getString("color_bg", null), base.bg)
        val key = parse(p.getString("color_key", null), base.key)
        val keyFn = parse(p.getString("color_keyfn", null), base.keyFn)
        val text = parse(p.getString("color_text", null), base.text)
        val accent = parse(p.getString("color_accent", null), base.accent)
        return KbTheme(
            bg = bg, key = key, keyFn = keyFn,
            pressed = ColorUtils.blendARGB(key, accent, 0.35f),
            text = text,
            hint = ColorUtils.setAlphaComponent(text, 0x99),
            accent = accent,
            accentText = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE,
            popupBg = key
        )
    }

    /**
     * Material You: derive the palette from the system wallpaper's colors.
     *
     * Order of preference:
     *  1. WallpaperManager seed colors (real wallpaper, any Android 12+ device)
     *  2. System dynamic color resources (Monet palette, always present on 12+)
     *  3. The built-in DARK/LIGHT base
     *
     * A contrast guard runs over the wallpaper-derived palette; if the wallpaper
     * is so extreme that text would be unreadable, the system palette is used.
     */
    @RequiresApi(31)
    private fun materialYou(ctx: Context, night: Boolean): KbTheme {
        val seeds = runCatching {
            val wc = WallpaperManager.getInstance(ctx)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (wc != null) MaterialYouPalette.Seeds(
                primary = MaterialYouPalette.sanitize(wc.primaryColor.toArgb()),
                secondary = wc.secondaryColor?.let { MaterialYouPalette.sanitize(it.toArgb()) },
                tertiary = wc.tertiaryColor?.let { MaterialYouPalette.sanitize(it.toArgb()) }
            ) else null
        }.getOrNull()

        val fromWallpaper = seeds
            ?.takeIf { MaterialYouPalette.isValidSeed(it.primary) }
            ?.let { MaterialYouPalette.build(it, night) }
        if (fromWallpaper != null && MaterialYouPalette.contrastSafe(fromWallpaper)) {
            return fromWallpaper
        }
        return runCatching { dynamic(ctx, night) }.getOrElse { if (night) DARK else LIGHT }
    }

    @RequiresApi(31)
    private fun dynamic(ctx: Context, night: Boolean): KbTheme {
        fun c(id: Int) = ctx.getColor(id)
        return if (night) KbTheme(
            bg = c(android.R.color.system_neutral1_900),
            key = c(android.R.color.system_neutral1_800),
            keyFn = c(android.R.color.system_accent2_800),
            pressed = c(android.R.color.system_accent1_700),
            text = c(android.R.color.system_neutral1_100),
            hint = c(android.R.color.system_neutral2_300),
            accent = c(android.R.color.system_accent1_200),
            accentText = c(android.R.color.system_accent1_800),
            popupBg = c(android.R.color.system_neutral1_700)
        ) else KbTheme(
            bg = c(android.R.color.system_neutral1_100),
            key = c(android.R.color.system_neutral1_0),
            keyFn = c(android.R.color.system_accent2_200),
            pressed = c(android.R.color.system_accent1_200),
            text = c(android.R.color.system_neutral1_900),
            hint = c(android.R.color.system_neutral2_600),
            accent = c(android.R.color.system_accent1_600),
            accentText = c(android.R.color.system_neutral1_0),
            popupBg = c(android.R.color.system_neutral1_0)
        )
    }
}
