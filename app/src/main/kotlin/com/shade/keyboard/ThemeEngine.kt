package com.shade.keyboard

/**
 * Theme engine ported from Expressive-Keyboard's "Colors & background"
 * (SettingsAppearance.kt / ThemeOptions.kt / Design.kt, v11.2.6, commit
 * 727393a), adapted to Shade's View-based renderer.
 *
 * Two layers, in Expressive's order:
 *
 *  1. [DesignPresets] — one-tap full-keyboard designs (Classic, Liquid Glass,
 *     Neon, Neumorphism, Samsung One UI, Nothing). Colors are the ones
 *     Expressive ships in `assets/plugins` JSON skins + `Design.kt`; glass keys
 *     keep Expressive's translucent fills because Shade's renderer draws them
 *     straight onto the canvas. A preset REPLACES the palette from the theme
 *     selector (theme choice stays untouched and keeps Material You working
 *     when no preset is picked).
 *
 *  2. [ThemeOverrides] — manual per-slot colors on top of ANY theme. This is
 *     Expressive's model ("theme colors are only overridden where the theme
 *     does not set them"): every override is optional and falls back to the
 *     active base, so picking one color no longer forces Shade's legacy
 *     "custom" theme. Unset text over a manually overridden key color
 *     auto-derives a readable on-color (Expressive's `contentColorFor`),
 *     which the legacy custom() never did.
 *
 * This file is deliberately pure Kotlin (no android.* imports) so the whole
 * engine runs — and is unit-tested — on the plain JVM.
 */

/** The nine palette slots Shade's renderer consumes. Pure, no android deps. */
data class KbTheme(
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

/**
 * Manual color overrides — Expressive's `color_*` rows. `null` = "auto",
 * i.e. keep the base theme's slot. Built by Theme.kt from the prefs.
 */
data class ThemeOverrides(
    val bg: Int? = null,
    val key: Int? = null,
    val keyFn: Int? = null,
    val text: Int? = null,
    val accent: Int? = null
) {
    val isEmpty: Boolean get() = bg == null && key == null && keyFn == null && text == null && accent == null

    /**
     * Layers these manual overrides onto any base palette (Expressive
     * semantics: every slot is optional and falls back to the base).
     *
     * Whenever at least one override is present, the four derived slots are
     * recomputed from the final five core slots — the exact derivation the
     * legacy custom() used, so a theme="custom" user sees bit-identical
     * colors after this port:
     *  - pressed = blend(key, accent, 0.35)
     *  - hint    = text @ 0x99 alpha
     *  - accentText = contrast-derived from accent (Expressive's
     *    contentColorFor, via ColorMath.contrastTextColor)
     *  - popupBg = key
     * An empty override set is an identity (the base palette comes through
     * untouched, hand-tuned pressed/hint/accentText included).
     */
    fun applyTo(base: KbTheme): KbTheme {
        if (isEmpty) return base
        val k = key ?: base.key
        val t = when {
            text != null -> text
            this.key != null -> ColorMath.contrastTextColor(k)
            else -> base.text
        }
        val acc = accent ?: base.accent
        return KbTheme(
            bg = bg ?: base.bg,
            key = k,
            keyFn = keyFn ?: base.keyFn,
            pressed = ColorMath.blendArgb(k, acc, 0.35f),
            text = t,
            hint = ColorMath.withAlpha(t, 0x99),
            accent = acc,
            accentText = ColorMath.contrastTextColor(acc),
            popupBg = k
        )
    }
}

/**
 * A one-tap design preset. [dark] colors are used in night mode when present;
 * when null the design is mode-independent (Neon ships one palette for both
 * modes in Expressive too).
 */
data class DesignPreset(
    val id: String,
    val light: KbTheme,
    val dark: KbTheme?
)

object DesignPresets {

    /** Builds a full palette around five core slots with Shade's derivation rules. */
    private fun palette(bg: Int, key: Int, keyFn: Int, text: Int, accent: Int): KbTheme = KbTheme(
        bg = bg,
        key = key,
        keyFn = keyFn,
        pressed = ColorMath.blendArgb(key, accent, 0.35f),
        text = text,
        hint = ColorMath.withAlpha(text, 0x99),
        accent = accent,
        accentText = ColorMath.contrastTextColor(accent),
        popupBg = key
    )

    /**
     * The presets, in catalog order. Colors traced from Expressive v11.2.6:
     * classic/neumorph/samsung/nothing/glass from the `assets/plugins` JSON skins
     * `skin.colors` / `skin.dark.colors`; glass translucency from Design.kt's
     * mini preview (keys white 60% / func white 40% in light, 22% / 12% in
     * dark over the flat glass background); neon from `neon.json` (single
     * palette, no dark variant).
     */
    val ALL: List<DesignPreset> = listOf(
        DesignPreset(
            "classic",
            palette(0xFFD1D4DA.toInt(), 0xFFFFFFFF.toInt(), 0xFFACB1BB.toInt(), 0xFF1C1C1E.toInt(), 0xFF0A7AFF.toInt()),
            palette(0xFF1C1C1E.toInt(), 0xFF4A4A4E.toInt(), 0xFF2E2E31.toInt(), 0xFFF2F2F7.toInt(), 0xFF0A84FF.toInt())
        ),
        DesignPreset(
            "glass",
            palette(0xFFE6EAF2.toInt(), 0x99FFFFFF.toInt(), 0x66FFFFFF.toInt(), 0xFF15171C.toInt(), 0xFF2F6BFF.toInt()),
            palette(0xFF12151F.toInt(), 0x38FFFFFF.toInt(), 0x1FFFFFFF.toInt(), 0xFFF4F6FB.toInt(), 0xFF5C8DFF.toInt())
        ),
        DesignPreset(
            "neon",
            palette(0xFF0E0B1B.toInt(), 0xFF161229.toInt(), 0xFF161229.toInt(), 0xFFE6FBFF.toInt(), 0xFF22D3EE.toInt()),
            null
        ),
        DesignPreset(
            "neumorph",
            palette(0xFFE6EAF1.toInt(), 0xFFE6EAF1.toInt(), 0xFFE6EAF1.toInt(), 0xFF39404F.toInt(), 0xFF6B7FF0.toInt()),
            palette(0xFF262A32.toInt(), 0xFF262A32.toInt(), 0xFF262A32.toInt(), 0xFFDDE2EC.toInt(), 0xFF7C8EFF.toInt())
        ),
        DesignPreset(
            "samsung",
            palette(0xFFECECEF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCDCE2.toInt(), 0xFF111111.toInt(), 0xFF3E91FF.toInt()),
            palette(0xFF101012.toInt(), 0xFF2B2B2E.toInt(), 0xFF202022.toInt(), 0xFFF2F2F2.toInt(), 0xFF3E91FF.toInt())
        ),
        DesignPreset(
            "nothing",
            palette(0xFFF2F2F2.toInt(), 0xFFFFFFFF.toInt(), 0xFFE4E4E4.toInt(), 0xFF000000.toInt(), 0xFFD71921.toInt()),
            palette(0xFF000000.toInt(), 0xFF121212.toInt(), 0xFF1D1D1D.toInt(), 0xFFFFFFFF.toInt(), 0xFFD71921.toInt())
        )
    )

    val IDS: List<String> = ALL.map { it.id }

    fun byId(id: String?): DesignPreset? = ALL.firstOrNull { it.id == id }

    /** Resolves a preset for the current mode; null when id is blank/unknown. */
    fun resolve(id: String?, night: Boolean): KbTheme? {
        val p = byId(id) ?: return null
        val dark = p.dark
        return if (night && dark != null) dark else p.light
    }
}
