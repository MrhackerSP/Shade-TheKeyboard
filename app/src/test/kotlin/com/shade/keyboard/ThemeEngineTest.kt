package com.shade.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the theme engine ported from Expressive-Keyboard's
 * "Colors & background" (DesignPresets + ThemeOverrides).
 *
 * Pinned color vectors were computed independently in Python (the same
 * round-half-up arithmetic androidx ColorUtils.blendARGB documents), not by
 * running the implementation.
 */
class ThemeEngineTest {

    // ── Design presets ─────────────────────────────────────────────────────

    @Test
    fun presets_haveStableIds() {
        assertEquals(
            listOf("classic", "glass", "neon", "neumorph", "samsung", "nothing"),
            DesignPresets.ALL.map { it.id }
        )
        assertEquals(DesignPresets.ALL.size, DesignPresets.IDS.toSet().size)
    }

    @Test
    fun resolve_blankOrUnknown_isNull() {
        assertNull(DesignPresets.resolve(null, night = false))
        assertNull(DesignPresets.resolve("", night = false))
        assertNull(DesignPresets.resolve("minecraft", night = false))
        assertNull(DesignPresets.resolve("NEON", night = false)) // ids are lowercase
    }

    @Test
    fun classic_matchesExpressivePalettes() {
        val light = DesignPresets.resolve("classic", night = false)!!
        assertEquals(0xFFD1D4DA.toInt(), light.bg)
        assertEquals(0xFFFFFFFF.toInt(), light.key)
        assertEquals(0xFFACB1BB.toInt(), light.keyFn)
        assertEquals(0xFF1C1C1E.toInt(), light.text)
        assertEquals(0xFF0A7AFF.toInt(), light.accent)
        // pressed = blend(key, accent, 0.35), computed independently.
        assertEquals(0xFFA9D0FF.toInt(), light.pressed)
        assertEquals(0xFFFFFFFF.toInt(), light.accentText) // accent luminance 0.212 < 0.5

        val dark = DesignPresets.resolve("classic", night = true)!!
        assertEquals(0xFF1C1C1E.toInt(), dark.bg)
        assertEquals(0xFF4A4A4E.toInt(), dark.key)
        assertEquals(0xFF2E2E31.toInt(), dark.keyFn)
        assertEquals(0xFFF2F2F7.toInt(), dark.text)
        assertEquals(0xFF0A84FF.toInt(), dark.accent)
        assertEquals(0xFF345E8C.toInt(), dark.pressed)
    }

    @Test
    fun glass_keysAreTranslucent() {
        val light = DesignPresets.resolve("glass", night = false)!!
        assertEquals(0x99FFFFFF.toInt(), light.key)  // Expressive mini preview: 60% white
        assertEquals(0x66FFFFFF.toInt(), light.keyFn) // 40% white
        assertEquals(0xFFE6EAF2.toInt(), light.bg)
        assertEquals(0xFF15171C.toInt(), light.text)
        assertEquals(0xFF2F6BFF.toInt(), light.accent)

        val dark = DesignPresets.resolve("glass", night = true)!!
        assertEquals(0x38FFFFFF.toInt(), dark.key)  // 22% white
        assertEquals(0x1FFFFFFF.toInt(), dark.keyFn) // 12% white
        assertEquals(0xFF12151F.toInt(), dark.bg)
        assertEquals(0xFF5C8DFF.toInt(), dark.accent)
    }

    @Test
    fun neon_isModeIndependent() {
        val light = DesignPresets.resolve("neon", night = false)!!
        val dark = DesignPresets.resolve("neon", night = true)!!
        assertEquals(light, dark)
        assertEquals(0xFF0E0B1B.toInt(), light.bg)
        assertEquals(0xFF22D3EE.toInt(), light.accent)
        // accent luminance 0.531 > 0.5 → black on-canvas text
        assertEquals(0xFF000000.toInt(), light.accentText)
        assertEquals(0xFF1A566E.toInt(), light.pressed)
    }

    @Test
    fun neumorph_keysMatchBackground() {
        // Expressive's neumorphism: keys carved from the background material.
        val light = DesignPresets.resolve("neumorph", night = false)!!
        assertEquals(light.bg, light.key)
        assertEquals(light.bg, light.keyFn)
        assertEquals(0xFF6B7FF0.toInt(), light.accent)
        assertEquals(0xFFBBC5F1.toInt(), light.pressed)
        val dark = DesignPresets.resolve("neumorph", night = true)!!
        assertEquals(0xFF262A32.toInt(), dark.bg)
        assertEquals(0xFF7C8EFF.toInt(), dark.accent)
        assertEquals(0xFF444D7A.toInt(), dark.pressed)
    }

    @Test
    fun samsung_andNothing_matchExpressivePalettes() {
        val samsungLight = DesignPresets.resolve("samsung", night = false)!!
        assertEquals(0xFFECECEF.toInt(), samsungLight.bg)
        assertEquals(0xFF3E91FF.toInt(), samsungLight.accent)
        assertEquals(0xFFBBD9FF.toInt(), samsungLight.pressed)
        val samsungDark = DesignPresets.resolve("samsung", night = true)!!
        assertEquals(0xFF101012.toInt(), samsungDark.bg)
        assertEquals(0xFF2B2B2E.toInt(), samsungDark.key)

        val nothingLight = DesignPresets.resolve("nothing", night = false)!!
        assertEquals(0xFFD71921.toInt(), nothingLight.accent) // Nothing red
        assertEquals(0xFFF1AFB1.toInt(), nothingLight.pressed)
        val nothingDark = DesignPresets.resolve("nothing", night = true)!!
        assertEquals(0xFF000000.toInt(), nothingDark.bg)
        assertEquals(0xFF121212.toInt(), nothingDark.key)
        assertEquals(0xFF571417.toInt(), nothingDark.pressed)
    }

    @Test
    fun presets_haveAllNineSlotsAndDistinctPalettes() {
        for (p in DesignPresets.ALL) {
            for (t in listOfNotNull(p.light, p.dark)) {
                // Every slot filled; popup follows key; hint follows text alpha.
                assertEquals(t.key, t.popupBg)
                assertEquals(ColorMath.withAlpha(t.text, 0x99), t.hint)
            }
        }
        val lights = DesignPresets.ALL.map { it.light }
        assertEquals(lights.size, lights.map { listOf(it.bg, it.key, it.accent) }.toSet().size)
    }

    // ── Overrides (Expressive semantics) ───────────────────────────────────

    // Local copies of Shade's DARK/LIGHT/AMOLED palettes — the tests must not
    // depend on Theme.kt (which imports android.* and stays out of the JVM
    // compile); values mirror Themes.kt exactly.
    private val base = KbTheme(
        bg = 0xFF1C1C1E.toInt(), key = 0xFF3C3C40.toInt(), keyFn = 0xFF2C2C2E.toInt(),
        pressed = 0xFF5A5A60.toInt(), text = 0xFFFFFFFF.toInt(), hint = 0xFF8E8E93.toInt(),
        accent = 0xFF8AB4F8.toInt(), accentText = 0xFF0B1B3A.toInt(), popupBg = 0xFF48484A.toInt()
    )
    private val lightBase = KbTheme(
        bg = 0xFFE9ECF1.toInt(), key = 0xFFFFFFFF.toInt(), keyFn = 0xFFCDD2DA.toInt(),
        pressed = 0xFFB7C3DA.toInt(), text = 0xFF1B1B1F.toInt(), hint = 0xFF6B6F76.toInt(),
        accent = 0xFF3B6FD4.toInt(), accentText = 0xFFFFFFFF.toInt(), popupBg = 0xFFFFFFFF.toInt()
    )
    private val amoledBase = KbTheme(
        bg = 0xFF000000.toInt(), key = 0xFF1C1C1E.toInt(), keyFn = 0xFF111112.toInt(),
        pressed = 0xFF3A3A3F.toInt(), text = 0xFFEDEDED.toInt(), hint = 0xFF8E9096.toInt(),
        accent = 0xFF8AB4F8.toInt(), accentText = 0xFF0B1B3A.toInt(), popupBg = 0xFF2A2A2D.toInt()
    )

    @Test
    fun apply_emptyOverride_isIdentity() {
        assertEquals(base, (ThemeOverrides()).applyTo(base))
    }

    @Test
    fun apply_bgOnly_keepsOtherSlotsDerived() {
        val out = (ThemeOverrides(bg = 0xFF112233.toInt())).applyTo(base)
        assertEquals(0xFF112233.toInt(), out.bg)
        // Derived slots recompute from the final core slots (legacy custom()
        // parity): pressed = blend(DARK.key, DARK.accent, 0.35).
        assertEquals(0xFF576680.toInt(), out.pressed)
        assertEquals(base.key, out.key)
        assertEquals(base.keyFn, out.keyFn)
        assertEquals(base.accent, out.accent)
        assertEquals(ColorMath.contrastTextColor(base.accent), out.accentText)
        assertEquals(base.key, out.popupBg)
        assertEquals(ColorMath.withAlpha(base.text, 0x99), out.hint)
    }

    @Test
    fun apply_keyOverride_autoDerivesReadableText() {
        // Expressive's contentColorFor: overriding the key color without an
        // explicit text override re-derives the on-key color.
        val darkKey = (ThemeOverrides(key = 0xFF112233.toInt())).applyTo(base)
        assertEquals(0xFFFFFFFF.toInt(), darkKey.text) // key luminance 0.015 → white text

        val lightKey = (ThemeOverrides(key = 0xFFFFE082.toInt())).applyTo(lightBase)
        assertEquals(0xFF000000.toInt(), lightKey.text) // key luminance 0.762 → black text
    }

    @Test
    fun apply_textOverride_winsOverAutoDerivation() {
        val out = (ThemeOverrides(key = 0xFF112233.toInt(), text = 0xFFFF00FF.toInt())).applyTo(base)
        assertEquals(0xFFFF00FF.toInt(), out.text)
        assertEquals(ColorMath.withAlpha(0xFFFF00FF.toInt(), 0x99), out.hint)
    }

    @Test
    fun apply_accentOverride_recolorsPressAndAccentText() {
        val out = (ThemeOverrides(accent = 0xFFFF0000.toInt())).applyTo(base)
        assertEquals(0xFFFF0000.toInt(), out.accent)
        assertEquals(0xFFFFFFFF.toInt(), out.accentText) // red luminance < 0.5
        assertEquals(ColorMath.blendArgb(base.key, 0xFFFF0000.toInt(), 0.35f), out.pressed)
    }

    @Test
    fun apply_fullSet_matchesLegacyCustomMath() {
        // Bit-exact parity with the pre-port Themes.custom() for the same
        // effective slots (bg/key/keyfn/text/accent + its derivations).
        val o = ThemeOverrides(
            bg = 0xFF112233.toInt(), key = 0xFF445566.toInt(), keyFn = 0xFF778899.toInt(),
            text = 0xFFEEDDCC.toInt(), accent = 0xFF8AB4F8.toInt()
        )
        val out = o.applyTo(base)
        assertEquals(0xFF112233.toInt(), out.bg)
        assertEquals(0xFF445566.toInt(), out.key)
        assertEquals(0xFF778899.toInt(), out.keyFn)
        assertEquals(0xFFEEDDCC.toInt(), out.text)
        assertEquals(0xFF8AB4F8.toInt(), out.accent)
        assertEquals(0xFF5D7699.toInt(), out.pressed) // blend(445566, 8AB4F8, 0.35), Python-checked
        assertEquals(0x99EEDDCC.toInt(), out.hint)
        assertEquals(0xFFFFFFFF.toInt(), out.accentText) // accent luminance 0.448 < 0.5
        assertEquals(0xFF445566.toInt(), out.popupBg)
    }

    @Test
    fun apply_overridesWorkOnAnyBase_notJustCustom() {
        // The Expressive behavior change: one color row now layers over the
        // active theme — here AMOLED — instead of forcing theme=custom.
        val out = (ThemeOverrides(accent = 0xFF00FF00.toInt())).applyTo(amoledBase)
        assertEquals(amoledBase.bg, out.bg)
        assertEquals(amoledBase.key, out.key)
        assertEquals(0xFF00FF00.toInt(), out.accent)
        assertNotEquals(amoledBase.accent, out.accent)
    }

    @Test
    fun overrides_isEmptyReportsCorrectly() {
        assertTrue(ThemeOverrides().isEmpty)
        assertFalse(ThemeOverrides(bg = 1).isEmpty)
        assertFalse(ThemeOverrides(key = 1).isEmpty)
        assertFalse(ThemeOverrides(keyFn = 1).isEmpty)
        assertFalse(ThemeOverrides(text = 1).isEmpty)
        assertFalse(ThemeOverrides(accent = 1).isEmpty)
    }

    @Test
    fun apply_keepsOverrideAlpha() {
        // 8-digit hex overrides preserve alpha (parity with the old
        // Color.parseColor path documented in ColorMath.normalizeHex).
        val out = (ThemeOverrides(bg = 0x80112233.toInt())).applyTo(base)
        assertEquals(0x80112233.toInt(), out.bg)
    }

    // ── ColorMath additions ─────────────────────────────────────────────────

    @Test
    fun blendArgb_matchesAndroidxBlendARGB() {
        // Round-half-up per channel; vectors computed in Python.
        assertEquals(0xFF576680.toInt(), ColorMath.blendArgb(0xFF3C3C40.toInt(), 0xFF8AB4F8.toInt(), 0.35f))
        assertEquals(0xFFA9D0FF.toInt(), ColorMath.blendArgb(0xFFFFFFFF.toInt(), 0xFF0A7AFF.toInt(), 0.35f))
        assertEquals(0x00000000, ColorMath.blendArgb(0x00000000, 0x00000000, 0.5f))
        // ratio 0 / 1 return the inputs (per-channel).
        assertEquals(0xFF112233.toInt(), ColorMath.blendArgb(0xFF112233.toInt(), 0xFF445566.toInt(), 0f))
        assertEquals(0xFF445566.toInt(), ColorMath.blendArgb(0xFF112233.toInt(), 0xFF445566.toInt(), 1f))
        // alpha participates: translucent key blended against opaque accent.
        assertEquals(0xBDB6CBFF.toInt(), ColorMath.blendArgb(0x99FFFFFF.toInt(), 0xFF2F6BFF.toInt(), 0.35f))
    }

    @Test
    fun withAlpha_replacesOnlyAlphaChannel() {
        assertEquals(0x991B1B1F.toInt(), ColorMath.withAlpha(0xFF1B1B1F.toInt(), 0x99))
        assertEquals(0x00FFFFFF, ColorMath.withAlpha(0xFF000000.toInt() or 0xFFFFFF, 0x00))
    }
}
