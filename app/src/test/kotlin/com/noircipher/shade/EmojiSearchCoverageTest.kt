package com.noircipher.shade

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the contract between the generated catalog (EmojiCatalog.kt) and the
 * keyword tables the search bar uses: every emoji shown in the grid must be
 * reachable through assets/emoji/emoji_keywords_*.tsv, otherwise a user could
 * see an emoji but never find it via search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiSearchCoverageTest {

    /** The tables EmojiPanel.loadKeywordMap() reads, in the same order. */
    private val tables = listOf("en", "hi", "concepts", "catalog", "shortcuts")

    @Test
    fun everyCatalogEmojiIsFindableThroughTheKeywordTables() {
        val context: Context = RuntimeEnvironment.getApplication()
        val covered = HashSet<String>()
        for (tag in tables) {
            context.assets.open("emoji/emoji_keywords_$tag.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val emoji = parts[1].trim()
                    if (emoji.isNotEmpty()) covered.add(emoji.replace("\uFE0F", ""))
                }
            }
        }
        val missing = EmojiCatalog.categories
            .flatMap { it.emojis }
            .filter { it.replace("\uFE0F", "") !in covered }
        assertTrue(
            "${missing.size} catalog emojis have no search keyword, e.g. ${missing.take(8)}",
            missing.isEmpty()
        )
    }

    /**
     * Pins the related-emoji asset: parses with the panel's parser, covers a
     * healthy share of the keyword universe, stays inside the generator's
     * per-emoji cap, and resolves the sample pairs the generator pinned.
     */
    @Test
    fun relatedAssetParsesAndPointsAtTheKeywordUniverse() {
        val context: Context = RuntimeEnvironment.getApplication()
        val related = context.assets.open("emoji/emoji_related.tsv").bufferedReader().useLines {
            EmojiSearch.parseRelatedTsv(it)
        }
        assertTrue(
            "related table too small: ${related.size} emoji covered",
            related.size >= 800
        )
        assertTrue(related.values.all { it.size <= 6 })
        assertTrue(related["🐶"].orEmpty().contains("🐕"))
        assertTrue(related["🚢"].orEmpty().contains("⚓"))
    }

    /**
     * Pins the synonym bridge asset (the model-free stand-in for Gboard's
     * Gemma embedding tier): parses with the panel's parser, covers a healthy
     * number of gap words, stays inside the alt cap, every target resolves to
     * a real corpus keyword, and no key duplicates a keyword the corpus
     * already covers — the bridge must be purely additive.
     */
    @Test
    fun synonymAssetBridgesIntoTheKeywordUniverse() {
        val context: Context = RuntimeEnvironment.getApplication()
        val synonyms = context.assets.open("emoji/emoji_synonyms.tsv").bufferedReader().useLines {
            EmojiSearch.parseSynonymTsv(it)
        }
        assertTrue("synonym bridge too small: ${synonyms.size} rows", synonyms.size >= 150)
        assertTrue(synonyms.values.all { it.size <= EmojiSearch.SYNONYM_ALT_CAP })

        val corpus = HashSet<String>()
        for (tag in tables) {
            context.assets.open("emoji/emoji_keywords_$tag.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val kw = parts[0].trim().lowercase()
                    if (kw.isNotEmpty()) corpus.add(kw)
                }
            }
        }
        val dangling = synonyms.flatMap { (_, alts) -> alts.filter { it !in corpus } }
        assertTrue(
            "bridge targets missing from the keyword corpus: ${dangling.take(8)}",
            dangling.isEmpty()
        )
        val redundant = synonyms.keys.filter { it in corpus }
        assertTrue(
            "bridge keys the corpus already covers (dead weight): ${redundant.take(8)}",
            redundant.isEmpty()
        )
    }
}
