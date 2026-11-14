package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji panel grid is generated from the official Unicode CLDR fully-qualified
 * list (Emoji 18.0 / Unicode 18.0, September 2026). These tests pin the structural
 * guarantees EmojiPanel relies on: nine categories in panel order, non-empty
 * sections, no duplicate or invalid glyphs, recent additions present, skin-tone
 * variants excluded (they live behind the long-press popup), and the official
 * Emoji 16.0/17.0/18.0 additions included as the user requested.
 */
class EmojiCatalogTest {

    private val all: Set<String> =
        EmojiCatalog.categories.flatMap { it.emojis }.toSet()

    @Test
    fun catalogHasTheStandardNineCategories() {
        assertEquals(
            listOf(
                "Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink",
                "Activities", "Travel & Places", "Objects", "Symbols", "Flags"
            ),
            EmojiCatalog.categories.map { it.name }
        )
    }

    @Test
    fun everyCategoryHasAnIconAndEmojis() {
        for (category in EmojiCatalog.categories) {
            assertTrue("icon missing for ${category.name}", category.icon.isNotBlank())
            assertTrue("category ${category.name} is empty", category.emojis.isNotEmpty())
        }
    }

    @Test
    fun emojisAreUniqueAcrossTheCatalog() {
        val flat = EmojiCatalog.categories.flatMap { it.emojis }
        assertEquals("duplicate emoji entries", flat.size, flat.toSet().size)
    }

    @Test
    fun entriesAreGlyphRunsWithoutAsciiLetters() {
        for (category in EmojiCatalog.categories) {
            for (emoji in category.emojis) {
                assertTrue("blank entry in ${category.name}", emoji.isNotBlank())
                assertTrue(
                    "ascii letters inside $emoji (${category.name})",
                    emoji.none { it in 'a'..'z' || it in 'A'..'Z' }
                )
            }
        }
    }

    @Test
    fun catalogIncludesRecentEmojiAdditions() {
        // Emoji 13.0–15.1 additions the old hand-maintained panel missed entirely.
        val recent = listOf(
            "🥲", "🥸", "🤌", "🫀", "🫁", "🥷", "🐻‍❄️", "🧋", "🫐", "🫒", "🫑",
            "🫠", "🫢", "🫣", "🫡", "🫤", "🫧", "🪬", "🪷", "🫘", "🫙", "🛝",
            "🥹", "🫨", "🫏", "🫎", "🪿", "🪽", "🪻", "🪸", "🪭", "🪮", "🪈", "🪯",
            "🫸", "🫷", "🙂‍↕️", "🙂‍↔️", "🍋‍🟩", "🐦‍🔥", "🍄‍🟫", "⛓️‍💥"
        )
        val missing = recent.filter { it !in all }
        assertTrue("missing recent emojis: $missing", missing.isEmpty())
    }

    @Test
    fun catalogIncludesEmoji16_0Additions() {
        // Emoji 16.0 (2024): face with bags under eyes, fingerprint, leafless tree,
        // root vegetable, harp, shovel, splatter.
        val added = listOf("🫩", "🫆", "🪾", "🫜", "🪉", "🪏", "🫟")
        val missing = added.filter { it !in all }
        assertTrue("missing Emoji 16.0 additions: $missing", missing.isEmpty())
    }

    @Test
    fun catalogIncludesEmoji17_0Additions() {
        // Emoji 17.0 (2025): distorted face, hairy creature, fight cloud, orca,
        // landslide, treasure chest, trombone, ballet dancer.
        val added = listOf("🫪", "🫈", "🫯", "🫍", "🛘", "🪎", "🪊", "🧑‍🩰")
        val missing = added.filter { it !in all }
        assertTrue("missing Emoji 17.0 additions: $missing", missing.isEmpty())
    }

    @Test
    fun catalogIncludesEmoji18_0Additions() {
        // Emoji 18.0 (September 2026): cracking face, leftwards/rightwards thumb
        // signs, monarch butterfly, pickle, lighthouse, meteor, eraser, net with handle.
        val added = listOf(
            "🫫", "🫹", "🫺", "🫌", "🫝", "🛙", "🪋", "🪌", "🪍"
        )
        val missing = added.filter { it !in all }
        assertTrue("missing Emoji 18.0 additions: $missing", missing.isEmpty())
        assertEquals("catalog must cover every emoji through Unicode 18.0", 1923, all.size)
    }

    @Test
    fun textPresentationEmojisCarryTheFe0fSelector() {
        // The Unicode chart lists text-default emojis without FE0F; committing them
        // bare would render monochrome text glyphs. The generator restores the
        // fully-qualified forms, so pin a few here.
        for (qualified in listOf("❤️", "☺️", "✌️", "©️", "#️⃣", "⬆️", "⏸️")) {
            assertTrue("missing fully-qualified $qualified", qualified in all)
        }
    }

    @Test
    fun skinToneVariantsAreExcludedFromTheGrid() {
        // Variants are reachable via long-press on the base emoji, not the grid.
        val variants = listOf("👋🏻", "👋🏽", "👋🏿", "👍🏼", "✌🏽", "🤝🏾", "🙏🏿", "🫹🏻")
        val present = variants.filter { it in all }
        assertTrue("skin-tone variants leaked into the grid: $present", present.isEmpty())
        // The base emoji itself must be there.
        assertTrue("👋" in all)
        assertTrue("✌️" in all)
        assertTrue("🫹" in all)
        assertTrue("🫺" in all)
    }

    @Test
    fun flagsCategoryIncludesCommonCountryFlags() {
        for (flag in listOf("🇮🇳", "🇺🇸", "🇬🇧", "🇯🇵", "🇩🇪", "🇧🇷", "🏳️‍🌈", "🏁", "🚩")) {
            assertTrue("missing flag $flag", flag in all)
        }
    }
}
