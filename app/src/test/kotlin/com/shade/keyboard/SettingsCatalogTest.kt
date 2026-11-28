package com.shade.keyboard

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
    fun sections_haveUniqueIds_inDisplayOrder() {
        val ids = SettingsCatalog.sections.map { it.id }
        assertEquals("duplicate section id", ids.size, ids.toSet().size)

        // Every declared section constant appears exactly once.
        assertEquals(
            setOf(
                SettingsCatalog.SECTION_LANGUAGES, SettingsCatalog.SECTION_KEYS,
                SettingsCatalog.SECTION_LAYOUT, SettingsCatalog.SECTION_SHORTCUTS,
                SettingsCatalog.SECTION_KEY_TAP, SettingsCatalog.SECTION_APPEARANCE,
                SettingsCatalog.SECTION_COLORS, SettingsCatalog.SECTION_BACKGROUND,
                SettingsCatalog.SECTION_CORRECTIONS, SettingsCatalog.SECTION_SUGGESTIONS,
                SettingsCatalog.SECTION_GESTURES, SettingsCatalog.SECTION_VOICE_MAIN,
                SettingsCatalog.SECTION_VOICE, SettingsCatalog.SECTION_EMOJI,
                SettingsCatalog.SECTION_GIF, SettingsCatalog.SECTION_CLIPBOARD,
                SettingsCatalog.SECTION_DICTIONARY, SettingsCatalog.SECTION_PRIVACY,
                SettingsCatalog.SECTION_SHARING, SettingsCatalog.SECTION_ABOUT,
                SettingsCatalog.SECTION_FEATURES, SettingsCatalog.SECTION_ENGINE,
                SettingsCatalog.SECTION_BACKUP, SettingsCatalog.SECTION_UPDATES
            ),
            ids.toSet()
        )

        // Display order follows the HOME SCREEN's page groups, not the numeric id
        // value: Advanced owns 21..23, Updates owns 20, yet Updates is the last page.
        // homeGroups itself is hard-pinned by home_listsEveryPageExactlyOnce_inGboardGroups,
        // so this also catches `pages` drifting out of sync with the home screen.
        val expectedOrder = SettingsCatalog.homeGroups.flatten()
            .flatMap { pageId -> SettingsCatalog.page(pageId)!!.sections.map { it.id } }
        assertEquals(expectedOrder, ids)
    }

    @Test
    fun home_listsEveryPageExactlyOnce_inGboardGroups() {
        val shown = SettingsCatalog.homeGroups.flatten()
        assertEquals("a page is listed twice on the home", shown.size, shown.toSet().size)
        assertEquals(SettingsCatalog.pages.map { it.id }.toSet(), shown.toSet())
        // Gboard's grouping: [Languages, Preferences, Theme] / [Corrections, Glide,
        // Voice, Emojis] / [Clipboard, Dictionary] / [Privacy, About, Advanced, Updates].
        assertEquals(
            listOf(
                listOf(SettingsCatalog.PAGE_LANGUAGES, SettingsCatalog.PAGE_PREFERENCES, SettingsCatalog.PAGE_THEME),
                listOf(SettingsCatalog.PAGE_CORRECTIONS, SettingsCatalog.PAGE_GLIDE,
                    SettingsCatalog.PAGE_VOICE, SettingsCatalog.PAGE_EMOJI),
                listOf(SettingsCatalog.PAGE_CLIPBOARD, SettingsCatalog.PAGE_DICTIONARY),
                listOf(SettingsCatalog.PAGE_PRIVACY, SettingsCatalog.PAGE_ABOUT,
                    SettingsCatalog.PAGE_ADVANCED, SettingsCatalog.PAGE_UPDATES)
            ),
            SettingsCatalog.homeGroups
        )
    }

    @Test
    fun pages_haveUniqueIds_titlesAndIcons_andContent() {
        val ids = SettingsCatalog.pages.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in SettingsCatalog.pages) {
            assertTrue("page ${p.id} has no title", p.titleRes != 0)
            assertTrue("page ${p.id} has no icon", p.iconRes != 0)
            assertTrue("page ${p.id} has no sections", p.sections.isNotEmpty())
        }
        assertEquals(null, SettingsCatalog.page(99))
    }

    @Test
    fun onlyTheVoiceMasterSwitch_isAHero() {
        val heroes = toggles.filter { it.hero }.map { it.key }
        assertEquals(listOf("voice_input"), heroes)
    }

    @Test
    fun noSectionIsAWall() {
        // The old Appearance section held 31 rows. Keep every group scannable.
        for (s in SettingsCatalog.sections) {
            assertTrue("section ${s.id} has ${s.items.size} rows", s.items.size <= 14)
        }
    }

    @Test
    fun longChoices_areTheOnesThatNeedAPicker() {
        // >3 options render as a value row + dialog; ≤3 as a segmented control.
        val long = choices.filter { it.options.size > 3 }.map { it.key }.toSet()
        assertEquals(setOf("theme", "design", "layout"), long)
    }

    @Test
    fun layoutChoice_onlyOffersKnownLayouts() {
        val layout = choices.first { it.key == "layout" }
        assertEquals("qwerty", layout.default)
        assertTrue(layout.options.map { it.first }.all { it in Layouts.LAYOUT_IDS })
    }

    @Test
    fun settings_liveInTheirIntendedSections() {
        fun keysIn(id: Int) = SettingsCatalog.section(id)!!.items.mapNotNull {
            when (it) {
                is SettingsItem.Toggle -> it.key
                is SettingsItem.Slider -> it.key
                is SettingsItem.Choice -> it.key
                is SettingsItem.Color -> it.key
                else -> null
            }
        }.toSet()
        fun keysOnPage(id: Int) = SettingsCatalog.page(id)!!.sections.flatMap { keysIn(it.id) }.toSet()

        assertTrue("layout" in keysIn(SettingsCatalog.SECTION_LANGUAGES))
        assertTrue("number_row" in keysIn(SettingsCatalog.SECTION_KEYS))
        assertTrue("key_height" in keysIn(SettingsCatalog.SECTION_LAYOUT))
        assertTrue("double_space" in keysIn(SettingsCatalog.SECTION_SHORTCUTS))
        assertTrue("haptic" in keysIn(SettingsCatalog.SECTION_KEY_TAP))
        assertTrue("repeat_delay" in keysIn(SettingsCatalog.SECTION_KEY_TAP))
        assertTrue("autocorrect" in keysIn(SettingsCatalog.SECTION_CORRECTIONS))
        assertTrue("suggestions" in keysIn(SettingsCatalog.SECTION_SUGGESTIONS))
        assertTrue("glide_typing" in keysIn(SettingsCatalog.SECTION_GESTURES))
        assertTrue("voice_input" in keysIn(SettingsCatalog.SECTION_VOICE_MAIN))
        assertTrue("emoji_suggestions" in keysIn(SettingsCatalog.SECTION_EMOJI))
        assertTrue("gif_enabled" in keysIn(SettingsCatalog.SECTION_GIF))
        assertTrue("clipboard_history" in keysIn(SettingsCatalog.SECTION_CLIPBOARD))
        assertTrue("typing_insights" in keysIn(SettingsCatalog.SECTION_PRIVACY))
        assertTrue("contribute_data" in keysIn(SettingsCatalog.SECTION_SHARING))
        assertTrue("learning_ranker" in keysIn(SettingsCatalog.SECTION_ENGINE))
        assertTrue("typing_insights" !in keysIn(SettingsCatalog.SECTION_ABOUT))

        // Page level: the categories Gboard users expect.
        assertTrue("theme" in keysOnPage(SettingsCatalog.PAGE_THEME))
        assertTrue("color_bg" in keysOnPage(SettingsCatalog.PAGE_THEME))
        assertTrue("bg_blur" in keysOnPage(SettingsCatalog.PAGE_THEME))
        assertTrue("sound" in keysOnPage(SettingsCatalog.PAGE_PREFERENCES))
        assertTrue("trackpad_mode" in keysOnPage(SettingsCatalog.PAGE_GLIDE))
        assertTrue("voice_on_device" in keysOnPage(SettingsCatalog.PAGE_VOICE))
        assertTrue("strip_voice_button" in keysOnPage(SettingsCatalog.PAGE_VOICE))
        assertTrue("learn_words" in keysOnPage(SettingsCatalog.PAGE_PRIVACY))
        assertTrue("translator_enabled" in keysOnPage(SettingsCatalog.PAGE_ADVANCED))
        assertTrue("auto_update_check" in keysOnPage(SettingsCatalog.PAGE_UPDATES))
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
        // The vibration-strength slider replaced the old haptic_strength
        // selector: 0-100 slider, default 50, step 1.
        val vib = sliders.first { it.key == "vibration_duration" }
        assertEquals(0, vib.min)
        assertEquals(100, vib.max)
        assertEquals(1, vib.step)
        assertEquals(50, vib.default)
        assertTrue(choices.none { it.key == "haptic_strength" })
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
        // Autos are the DARK theme tokens the override layer falls back to
        // (ThemeOverrides.apply with an unset slot keeps the base).
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
        assertTrue(toggleDefault("voice_on_device"))              // getBoolean("voice_on_device", true)
        assertTrue(toggleDefault("strip_voice_button"))           // ToolbarController: getBoolean("strip_voice_button", true)
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
        // Colors & background — PrefsApplier.applyPrefs().
        assertEquals(100, sliderDefault("key_opacity"))           // getInt("key_opacity", 100)
        assertEquals(0, sliderDefault("bg_transparency"))         // getInt("bg_transparency", 0)
        assertEquals(100, sliderDefault("photo_zoom"))            // getInt("photo_zoom", 100)
        assertEquals(100, sliderDefault("photo_width"))           // getInt("photo_width", 100)
        assertEquals(100, sliderDefault("photo_height"))          // getInt("photo_height", 100)
        assertEquals(0, sliderDefault("photo_x"))                 // getInt("photo_x", 0)
        assertEquals(0, sliderDefault("photo_y"))                 // getInt("photo_y", 0)
        assertEquals(35, sliderDefault("bg_dim"))                 // getInt("bg_dim", 35)
        assertEquals(0, sliderDefault("bg_blur"))                 // getInt("bg_blur", 0)
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

        val gifKey = SettingsCatalog.search("gif")
        assertTrue(gifKey.any { it is SettingsItem.Action })
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
    fun search_singleLetter_onlyMatchesKeywordPrefixes() {
        val hits = SettingsCatalog.search("a")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { item -> item.keywords.any { it.startsWith("a") } })
    }

    @Test
    fun search_shortKeywordsDoNotMatchInsideLongerWords() {
        // "ai" is a keyword of the neural toggle; "fair" must not find it.
        val hits = SettingsCatalog.search("fair")
        assertTrue(hits.none { it is SettingsItem.Toggle && it.key == "neural_predictions" })
    }

    @Test
    fun search_matchesVisibleTitles_whenAResolverIsSupplied() {
        val titles = { res: Int -> if (res == R.string.pref_autocorrect) "Zebra title" else "" }
        val hits = SettingsCatalog.search("zebra", titles)
        assertEquals(1, hits.size)
        assertTrue(hits[0] is SettingsItem.Toggle && (hits[0] as SettingsItem.Toggle).key == "autocorrect")
        assertTrue(SettingsCatalog.search("zebra").isEmpty())
    }

    @Test
    fun section_lookup() {
        assertNotNull(SettingsCatalog.section(SettingsCatalog.SECTION_CORRECTIONS))
        assertEquals(null, SettingsCatalog.section(99))
    }
}
