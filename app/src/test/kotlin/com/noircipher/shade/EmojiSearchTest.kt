package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking semantics of the emoji panel's search bar. Every catalog emoji is
 * covered by the keyword tables (see EmojiSearchCoverageTest); here we pin how
 * queries turn into an ordered result list.
 */
class EmojiSearchTest {

    private val map = mapOf(
        "grinning face" to listOf("😀"),
        "grin" to listOf("😁"),
        "smile" to listOf("😊"),
        "smiling face" to listOf("😊", "🙂"),
        "face" to listOf("😀", "😊", "🤔"),
        "melting face" to listOf("🫠"),
        "moose" to listOf("🫎"),
        "india" to listOf("🇮🇳"),
        "flag: india" to listOf("🇮🇳")
    )

    @Test
    fun blankQueryYieldsNoResults() {
        assertTrue(EmojiSearch.search("", map).isEmpty())
        assertTrue(EmojiSearch.search("   ", map).isEmpty())
    }

    @Test
    fun exactKeywordRanksFirst() {
        // "smile" exactly matches one keyword (score -5). Note "smiling face" does
        // NOT contain the literal string "smile" (s-m-i-l-i-n-g), so it never
        // enters the candidate set — only the exact keyword contributes.
        assertEquals(listOf("😊"), EmojiSearch.search("smile", map))
    }

    @Test
    fun multiTokenQueryRequiresAllTokens() {
        // "grinning face" must not leak emojis from keywords that only match one token.
        assertEquals(listOf("😀"), EmojiSearch.search("grinning face", map))
    }

    @Test
    fun substringKeywordMatchesStillCount() {
        // "face" is contained in "smiling face" and "melting face" too.
        val results = EmojiSearch.search("face", map)
        assertTrue("🙂" in results)
        assertTrue("🫠" in results)
        // The short keyword itself ranks first.
        assertEquals("😀", results.first())
    }

    @Test
    fun caseInsensitive() {
        assertEquals(listOf("🫎"), EmojiSearch.search("MOOSE", map))
        assertEquals(listOf("🫎"), EmojiSearch.search("MoOsE", map))
    }

    @Test
    fun keywordSubstringSearchFindsFlags() {
        // The generated catalog table stores "flag: india"; a plain country name
        // query must still surface the flag.
        assertEquals(listOf("🇮🇳"), EmojiSearch.search("india", map))
    }

    @Test
    fun duplicateEmojisAcrossKeywordsAreDeduplicated() {
        // "face" lists 😀 😊 🤔; "smiling face" repeats 😊 before 🙂.
        val results = EmojiSearch.search("face", map)
        assertEquals(results.size, results.toSet().size)
    }

    @Test
    fun limitCapsResultCount() {
        val many = (1..20).associate { "keyword$it" to listOf("<$it>") }
        assertEquals(3, EmojiSearch.search("keyword", many, limit = 3).size)
        assertEquals(0, EmojiSearch.search("keyword", many, limit = 0).size)
    }

    @Test
    fun resultsStayUnderTheDefaultCap() {
        assertTrue(EmojiSearch.search("face", map).size <= EmojiSearch.MAX_RESULTS)
    }

    // ── Related-emoji fill ─────────────────────────────────────────────────

    private val related = mapOf(
        "🚢" to listOf("⚓", "⛵", "🛳️"),
        "😊" to listOf("🙂", "😌")
    )

    @Test
    fun relatedEmojisFillThinResults() {
        val tiny = mapOf("ship" to listOf("🚢"))
        assertEquals(listOf("🚢", "⚓", "⛵", "🛳️"), EmojiSearch.search("ship", tiny, relatedMap = related))
    }

    @Test
    fun relatedEmojisRespectTheLimit() {
        val tiny = mapOf("ship" to listOf("🚢"))
        val results = EmojiSearch.search("ship", tiny, limit = 2, relatedMap = related)
        assertEquals(listOf("🚢", "⚓"), results)
    }

    @Test
    fun relatedEmojisNeverDuplicateDirectMatches() {
        val tiny = mapOf("ship" to listOf("🚢"))
        val relatedWithDup = mapOf("🚢" to listOf("🚢", "⚓"))
        assertEquals(listOf("🚢", "⚓"), EmojiSearch.search("ship", tiny, relatedMap = relatedWithDup))
    }

    @Test
    fun relatedExpansionKeepsDirectOrderFirst() {
        val tiny = mapOf("ship" to listOf("🚢"), "anchor" to listOf("⚓"))
        val results = EmojiSearch.search("ship", tiny, relatedMap = related)
        // Direct hit first; related fill appends strictly afterwards.
        assertEquals(results.indexOf("🚢") + 1, results.indexOf("⚓"))
    }

    @Test
    fun noRelatedExpansionWithoutMatches() {
        assertTrue(EmojiSearch.search("zzzz", map, relatedMap = related).isEmpty())
    }

    // ── Fuzzy fallback ─────────────────────────────────────────────────────

    @Test
    fun fuzzyFallbackFixesTransposition() {
        // "moosee" is one transposition-style edit away from "moose"; the direct
        // pass finds nothing, the fuzzy pass must rescue it.
        assertEquals(listOf("🫎"), EmojiSearch.search("moosee", map))
        // "smiel" -> "smile"
        assertEquals(listOf("😊"), EmojiSearch.search("smiel", map))
    }

    @Test
    fun fuzzyFallbackHandlesMultiTokenQueries() {
        assertEquals(listOf("😀"), EmojiSearch.search("grining face", map))
    }

    @Test
    fun fuzzyStaysExactForShortTokens() {
        val tiny = mapOf("car" to listOf("🚗"))
        // 3-letter tokens tolerate zero edits: "cat" must not drift to "car".
        assertTrue(EmojiSearch.search("cat", tiny).isEmpty())
        assertTrue(EmojiSearch.search("car", tiny).isNotEmpty())
    }

    @Test
    fun directMatchesWinBeforeFuzzyRuns() {
        // "smile" matches directly; the fuzzy candidate "smial" must not appear.
        val m = map + mapOf("smial" to listOf("🤪"))
        assertEquals(listOf("😊"), EmojiSearch.search("smile", m))
    }

    @Test
    fun fuzzyResultsAlsoGetRelatedFill() {
        val tiny = mapOf("ship" to listOf("🚢"))
        assertEquals(
            listOf("🚢", "⚓"),
            EmojiSearch.search("shiip", tiny, limit = 2, relatedMap = related)
        )
    }

    @Test
    fun fuzzyRankingPrefersTheClosestKeyword() {
        // "lonley": "lonely" is one edit away, "honey" is two — the shorter but
        // further "honey" must not outrank the intended keyword.
        val m = mapOf("honey" to listOf("🍯"), "lonely" to listOf("😔"), "money" to listOf("💰"))
        assertEquals(listOf("😔"), EmojiSearch.search("lonley", m))
        // "moosee": "moose" (1 edit) beats "mouse" (2 edits).
        val zoo = mapOf("mouse" to listOf("🐭"), "moose" to listOf("🫎"))
        assertEquals(listOf("🫎"), EmojiSearch.search("moosee", zoo))
    }

    // ── Related TSV parsing ────────────────────────────────────────────────

    @Test
    fun parseRelatedTsvPreservesFileOrderAndSkipsComments() {
        val data = sequenceOf(
            "# comment line",
            "🚢\t⚓\t1.000",
            "🚢\t⛵\t0.800",
            "🚢\t🚢\t0.500",   // self rows are kept; caller never hits them (direct first)
            "",
            "😊\t🙂\t0.900"
        )
        val parsed = EmojiSearch.parseRelatedTsv(data)
        assertEquals(listOf("⚓", "⛵", "🚢"), parsed["🚢"])
        assertEquals(listOf("🙂"), parsed["😊"])
        assertTrue("🚕" !in parsed.keys.orEmpty())
    }

    // ── Synonym bridge ─────────────────────────────────────────────────────

    /** Curated bridge semantics: gap word -> canonical keywords, best first. */
    private val bridge = mapOf(
        "sob" to listOf("cry", "crying"),
        "gloomy" to listOf("sad", "down"),
        "anxious" to listOf("nervous")
    )
    private val cryMap = mapOf(
        "cry" to listOf("😢"),
        "crying" to listOf("😭", "😢"),
        "sad" to listOf("😔"),
        "down" to listOf("🙁"),
        "nervous" to listOf("😬")
    )

    @Test
    fun synonymBridgeFiresWhenDirectPassIsEmpty() {
        // "sob" matches no keyword directly; the bridge swaps it for "cry".
        assertEquals(listOf("😢"), EmojiSearch.search("sob", cryMap, synonymMap = bridge))
        // Case-insensitive like every other pass.
        assertEquals(listOf("😢"), EmojiSearch.search("SoB", cryMap, synonymMap = bridge))
    }

    @Test
    fun directMatchAlwaysBeatsTheBridge() {
        // "cry" matches directly, so the bridge must never run — even though
        // "sob" has a bridge entry in the same query's vocabulary.
        val results = EmojiSearch.search("cry", cryMap, synonymMap = bridge)
        assertEquals("😢", results.first())
    }

    @Test
    fun bridgeSwapsKeepTheRestOfTheQuery() {
        // "gloomy dog" -> swap "gloomy" for "sad" -> "sad dog" keyword.
        val m = mapOf("sad dog" to listOf("🐶"))
        val b = mapOf("gloomy" to listOf("sad", "down"))
        assertEquals(listOf("🐶"), EmojiSearch.search("gloomy dog", m, synonymMap = b))
    }

    @Test
    fun bridgeAloneTierFiresWhenSwapsStillMiss() {
        // No keyword contains "cry loudly" or "crying loudly", so the swap
        // tier misses; the alone tier matches each alternative on its own.
        val results = EmojiSearch.search("sob loudly", cryMap, synonymMap = bridge)
        assertEquals(listOf("😢"), results)
    }

    @Test
    fun bridgeNeverSubstitutesATokenAlreadyPresent() {
        // The only alternative for "gloomy" is "dog", which is already in the
        // query — substituting it would search "dog dog". The guard must skip
        // it, leaving no bridge results (and fuzzy can't rescue "gloomy").
        val m = mapOf("dog" to listOf("🐶"))
        val b = mapOf("gloomy" to listOf("dog"))
        assertTrue(EmojiSearch.search("gloomy dog", m, synonymMap = b).isEmpty())
    }

    @Test
    fun bridgeIsSkippedWhenNoMapIsLoaded() {
        // Asset missing/empty → v8 behavior unchanged: plain empty result.
        assertTrue(EmojiSearch.search("sob", cryMap).isEmpty())
    }

    @Test
    fun bridgeResultsAlsoGetRelatedFill() {
        val tiny = mapOf("cry" to listOf("😢"))
        val related = mapOf("😢" to listOf("😭"))
        assertEquals(
            listOf("😢", "😭"),
            EmojiSearch.search("sob", tiny, limit = 2, relatedMap = related, synonymMap = bridge)
        )
    }

    // ── Synonym TSV parsing ────────────────────────────────────────────────

    @Test
    fun parseSynonymTsvNormalizesCapsAndSkipsJunk() {
        val data = sequenceOf(
            "# generated header",
            "",
            "sob\tcry,crying",
            "Gloomy\tSAD, sad,,down",          // lowercased, dup removed, blank dropped
            "multi word\tnope",                 // keys are single tokens
            "self\tself",                       // a key may never bridge to itself
            "cap\tb1,b2,b3,b4,b5,b6",           // capped at SYNONYM_ALT_CAP
            "noalts\t,,"
        )
        val parsed = EmojiSearch.parseSynonymTsv(data)
        assertEquals(listOf("cry", "crying"), parsed["sob"])
        assertEquals(listOf("sad", "down"), parsed["gloomy"])
        assertEquals(listOf("b1", "b2", "b3", "b4"), parsed["cap"])
        assertEquals(EmojiSearch.SYNONYM_ALT_CAP, parsed["cap"]!!.size)
        assertTrue("multi word" !in parsed.keys.orEmpty())
        assertTrue("self" !in parsed.keys.orEmpty())
        assertTrue("noalts" !in parsed.keys.orEmpty())
    }
}
