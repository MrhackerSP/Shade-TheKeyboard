package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural integrity of the settings catalog: unique keys, sane sliders,
 * valid choices, lowercase keywords — plus the defaults parity contract with
 * ShadeService.applyPrefs() (a wrong default here would flip behavior on
 * first launch for anyone who never opens that row).
 */
class SettingsCatalogTest {

    private val toggles = SettingsCatalog.flat().filterIsInstance<SettingsItem.Toggle>()
    private val sliders = SettingsCatalog.flat().filterIsInstance<SettingsItem.Slider>()
    private val choices = SettingsCatalog.flat().filterIsInstance<SettingsItem.Choice>()
    private val colors = SettingsCatalog.flat().filterIsInstance<SettingsItem.Color>()
    private val actions = SettingsCatalog.flat().filterIsInstance<SettingsItem.Action>()

    // ── Structure ────────────────────────────────────────────────────────

    @Test
    fun sections_areFiveWithUniqueIds() {
        assertEquals(5, SettingsCatalog.sections.size)
        val ids = SettingsCatalog.sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf(
                SettingsCatalog.SECTION_APPEARANCE,
                SettingsCatalog.SECTION_TYPING,
                SettingsCatalog.SECTION_LANGUAGES,
                SettingsCatalog.SECTION_BACKUP,
                SettingsCatalog.SECTION_ABOUT
            ),
            ids
        )
    }

    @Test
    fun sections_areNonEmpty() {
        for (s in SettingsCatalog.sections) {
            assertTrue("section ${s.id} is empty", s.items.isNotEmpty())
        }
    }

    @Test
    fun keys_areUniqueAndNonBlank() {
        val keyed = (toggles.map { it.key } + choices.map { it.key } +
            sliders.map { it.key } + colors.map { it.key })
        assertTrue("blank key found", keyed.none { it.isBlank() })
        assertEquals("duplicate keys: ${keyed.groupBy { it }.filterValues { it.size > 1 }.keys}",
            keyed.size, keyed.toSet().size)
    }

    @Test
    fun everySearchableItem_hasLowercaseKeywords() {
        val searchable = toggles + sliders + choices + colors + actions
        for (item in searchable) {
            assertTrue("${item::class.simpleName} has no keywords", item.keywords.isNotEmpty())
            for (kw in item.keywords) {
                assertEquals("keyword '$kw' not lowercase/trimmed", kw, kw.trim().lowercase())
            }
        }
        assertTrue(searchable.isNotEmpty())
    }

    @Test
    fun choices_haveValidOptionsAndDefaults() {
        for (c in choices) {
            assertTrue("choice '${c.key}' needs ≥2 options", c.options.size >= 2)
            val values = c.options.map { it.first }
            assertEquals("duplicate option values in '${c.key}'", values.size, values.toSet().size)
            assertTrue("default '${c.default}' not among options of '${c.key}'",
                c.default in values)
            for ((_, labelRes) in c.options) {
                assertTrue(labelRes != 0)
            }
        }
        // The known choices carry the exact values ShadeService reads.
        assertEquals(setOf("system", "light", "dark", "amoled", "material", "custom"),
            choices.first { it.key == "theme" }.options.map { it.first }.toSet())
        assertEquals("system", choices.first { it.key == "theme" }.default)
        assertEquals(setOf("off", "left", "right"),
            choices.first { it.key == "one_handed" }.options.map { it.first }.toSet())
        assertEquals(setOf("off", "landscape", "always"),
            choices.first { it.key == "split_keyboard" }.options.map { it.first }.toSet())
        assertEquals(setOf("light", "medium", "strong"),
            choices.first { it.key == "haptic_strength" }.options.map { it.first }.toSet())
        assertEquals("medium", choices.first { it.key == "haptic_strength" }.default)
    }

    @Test
    fun sliders_haveSaneRangesAndSteps() {
        for (s in sliders) {
            assertTrue("slider '${s.key}': min must be < max", s.min < s.max)
            assertTrue("slider '${s.key}': step must be > 0", s.step > 0)
            assertEquals("slider '${s.key}': range not divisible by step",
                0, (s.max - s.min) % s.step)
            assertTrue("slider '${s.key}': default out of range",
                s.default in s.min..s.max)
        }
    }

    @Test
    fun colors_haveDistinctKeysAndOpaqueAutoColors() {
        assertEquals(
            listOf("color_bg", "color_key", "color_keyfn", "color_text", "color_accent"),
            colors.map { it.key }
        )
        // Autos are the DARK theme tokens that Themes.custom() falls back to.
        assertEquals(0xFF1C1C1E.toInt(), colors.first { it.key == "color_bg" }.autoColor)
        assertEquals(0xFF3C3C40.toInt(), colors.first { it.key == "color_key" }.autoColor)
        assertEquals(0xFF2C2C2E.toInt(), colors.first { it.key == "color_keyfn" }.autoColor)
        assertEquals(0xFFFFFFFF.toInt(), colors.first { it.key == "color_text" }.autoColor)
        assertEquals(0xFF8AB4F8.toInt(), colors.first { it.key == "color_accent" }.autoColor)
    }

    // ── Defaults parity with ShadeService.applyPrefs() ────────────────────

    private fun toggleDefault(key: String): Boolean =
        toggles.first { it.key == key }.default

    private fun sliderDefault(key: String): Int =
        sliders.first { it.key == key }.default

    @Test
    fun toggleDefaults_matchServiceReads() {
        // Each line matches the literal default in ShadeService.applyPrefs().
        assertTrue(toggleDefault("autocorrect"))
        assertTrue(toggleDefault("suggestions"))
        assertTrue(toggleDefault("neural_predictions"))
        assertFalse(toggleDefault("phrase_suggestions"))          // line: getBoolean("phrase_suggestions", false)
        assertTrue(toggleDefault("smart_correction"))
        assertTrue(toggleDefault("auto_caps"))
        assertTrue(toggleDefault("double_space"))
        assertTrue(toggleDefault("learn_words"))
        assertTrue(toggleDefault("autofill_chips"))               // getBoolean("autofill_chips", true)
        assertTrue(toggleDefault("glide_typing"))
        assertTrue(toggleDefault("trackpad_mode"))
        assertTrue(toggleDefault("haptic"))
        assertFalse(toggleDefault("sound"))
        assertFalse(toggleDefault("typing_burst"))
        assertFalse(toggleDefault("toolbar_open_default"))        // getBoolean("toolbar_open_default", false)
        assertFalse(toggleDefault("number_row"))
        assertTrue(toggleDefault("key_borders"))
        assertTrue(toggleDefault("show_hints"))
        assertTrue(toggleDefault("popup_preview"))
        assertTrue(toggleDefault("emoji_suggestions"))
        assertTrue(toggleDefault("gif_enabled"))
        assertTrue(toggleDefault("translator_enabled"))
        assertTrue(toggleDefault("voice_input"))
        assertTrue(toggleDefault("learning_adaptive"))
        assertTrue(toggleDefault("learning_appctx"))
        assertTrue(toggleDefault("learning_suppressor"))          // getBoolean("learning_suppressor", true)
        assertTrue(toggleDefault("learning_calibrator"))          // getBoolean("learning_calibrator", true)
        assertTrue(toggleDefault("learning_ranker"))
        assertTrue(toggleDefault("learning_correction"))          // getBoolean("learning_correction", true)
        assertTrue(toggleDefault("learning_touch"))               // getBoolean("learning_touch", true)
        assertFalse(toggleDefault("incognito"))
        assertTrue(toggleDefault("clipboard_history"))
        assertFalse(toggleDefault("contribute_data"))
        assertFalse(toggleDefault("typing_insights"))             // getBoolean("typing_insights", false)
        assertFalse(toggleDefault("auto_update_check"))
    }

    @Test
    fun sliderDefaults_matchServiceReads() {
        assertEquals(50, sliderDefault("key_height"))             // getInt("key_height", 50)
        assertEquals(300, sliderDefault("longpress_delay"))       // getInt("longpress_delay", 300)
        assertEquals(350, sliderDefault("repeat_delay"))          // getInt("repeat_delay", 350)
        assertEquals(40, sliderDefault("repeat_interval"))        // getInt("repeat_interval", 40)
    }

    // ── Search ───────────────────────────────────────────────────────────

    @Test
    fun search_emptyQuery_returnsNothing() {
        assertTrue(SettingsCatalog.search("").isEmpty())
        assertTrue(SettingsCatalog.search("   ").isEmpty())
    }

    @Test
    fun search_noMatch_returnsNothing() {
        assertTrue(SettingsCatalog.search("zzzqqq").isEmpty())
    }

    @Test
    fun search_findsItemsByKeyword() {
        val glide = SettingsCatalog.search("glide")
        assertEquals(1, glide.size)
        assertTrue(glide[0] is SettingsItem.Toggle && (glide[0] as SettingsItem.Toggle).key == "glide_typing")

        val mic = SettingsCatalog.search("mic")
        assertTrue(mic.any { it is SettingsItem.Toggle && (it as SettingsItem.Toggle).key == "voice_input" })

        val stats = SettingsCatalog.search("analytics")
        assertTrue(stats.isNotEmpty())
    }

    @Test
    fun search_matchesBidirectionally() {
        // Query is longer than the keyword ("glides" ⊃ "glide").
        assertTrue(SettingsCatalog.search("glides").isNotEmpty())
        // Query is shorter than the keyword ("mic" ⊂ "microphone").
        assertTrue(SettingsCatalog.search("mic").isNotEmpty())
    }

    @Test
    fun search_colors_returnsAllFiveColorRows() {
        val results = SettingsCatalog.search("color")
        val colorKeys = results.filterIsInstance<SettingsItem.Color>().map { it.key }
        assertEquals(setOf("color_bg", "color_key", "color_keyfn", "color_text", "color_accent"),
            colorKeys.toSet())
    }

    @Test
    fun section_lookup() {
        assertNotNull(SettingsCatalog.section(SettingsCatalog.SECTION_TYPING))
        assertEquals(null, SettingsCatalog.section(99))
    }
}
