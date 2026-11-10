package com.noircipher.shade

import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.slide.SlideDictionary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class SlideDecoderTest {

    /** Tiny stand-in for the real dictionary: [known] for contains(), [suggestions] for suggestAsync(). */
    private class FakeDictionary(
        private val known: Set<String> = emptySet(),
        private val suggestions: List<String> = emptyList()
    ) : SlideDictionary {
        override fun contains(word: String) = word in known
        override suspend fun suggestAsync(prefix: String, max: Int) = suggestions.take(max)
        override fun glideLexicon(): List<Pair<String, Float>> = known.map { it to 0f }
    }

    /** QWERTY with 100x100 keys; row 2 is offset by half a key, row 3 by one and a half. */
    private val layout: Map<Char, Key> = buildMap {
        val rows = listOf(Triple("qwertyuiop", 0f, 0f), Triple("asdfghjkl", 50f, 100f), Triple("zxcvbnm", 150f, 200f))
        for ((letters, offset, rowTop) in rows) {
            letters.forEachIndexed { i, c ->
                put(c, Key(c.toString(), c.code).apply {
                    left = offset + i * 100f; right = left + 100f
                    top = rowTop; bottom = rowTop + 100f
                })
            }
        }
    }

    private fun center(c: Char) = floatArrayOf(layout.getValue(c).centerX, layout.getValue(c).centerY)

    /**
     * Straight segments through the centres of [word]'s keys, one point every [step] px.
     * Keys whose index is in [loops] get a small circle (radius 25) drawn on them,
     * the gesture for a double letter.
     */
    private fun pathFor(word: String, loops: Set<Int> = emptySet(), step: Float = 9f): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val cs = word.map { center(it) }
        for (i in 0 until cs.size - 1) {
            val a = cs[i]; val b = cs[i + 1]
            val n = maxOf(1, (hypot(b[0] - a[0], b[1] - a[1]) / step).toInt())
            for (k in 0 until n) out.add(floatArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n))
            if ((i + 1) in loops) out.addAll(loopAround(word[i + 1]))
        }
        out.add(cs.last())
        if ((word.length - 1) in loops) out.addAll(loopAround(word.last()))
        return out
    }

    private fun loopAround(c: Char, radius: Float = 25f, points: Int = 8): List<FloatArray> {
        val m = center(c)
        return (0 until points).map { k ->
            val angle = 2.0 * Math.PI * k / points
            floatArrayOf(m[0] + (radius * cos(angle)).toFloat(), m[1] + (radius * sin(angle)).toFloat())
        }
    }

    private fun decode(path: List<FloatArray>, dict: SlideDictionary = FakeDictionary(), keys: Map<Char, Key> = layout) =
        runBlocking { SlideDecoder(dict).decode(path, keys) }

    // ---- decode ----

    @Test fun tooShortPathGivesNull() {
        assertNull(decode(pathFor("was").take(3)))
    }

    @Test fun emptyLayoutGivesNull() {
        assertNull(decode(pathFor("was"), keys = emptyMap()))
    }

    @Test fun dictionaryWordIsReturnedAsIs() {
        assertEquals("was", decode(pathFor("was"), FakeDictionary(known = setOf("was"))))
    }

    @Test fun rawKeySequenceIsReturnedWhenDictionaryHasNoSuggestions() {
        assertEquals("was", decode(pathFor("was")))
    }

    @Test fun diagonalSwipeDoesNotCommitEveryCrossedKey() {
        // A straight a->y swipe passes near s and t; those near-misses used to be
        // registered as crossings and the whole soup was committed verbatim.
        val decoded = decode(pathFor("ay"))
        assertTrue("got '$decoded'", decoded != null && decoded.length <= 4)
    }

    @Test fun subsequenceFallbackFindsTheIntendedWord() {
        // Finger crosses j and u between h and i; only "hi" is a dictionary word
        // whose letters appear in order along the crossed keys.
        val viaJU = pathFor("hjui")
        assertEquals("hi", decode(viaJU, FakeDictionary(known = setOf("hi"))))
    }

    @Test fun subsequenceFallbackRecoversDogFromCrossedSoup() {
        // d->o->g crosses f, u, i, h on the way; "dog" is a subsequence of that soup.
        val dict = FakeDictionary(known = setOf("dog", "dig", "fig"))
        val decoded = decode(pathFor("dog"), dict)
        assertTrue("expected a real word, got '$decoded'", decoded in setOf("dog", "dig", "fig"))
    }

    @Test fun suggestionsAreChosenByHowWellTheyFollowTheSwipe() {
        // Suggestions arrive in the "wrong" order; the swipe clearly went w -> a -> s.
        val dict = FakeDictionary(suggestions = listOf("wax", "was"))
        assertEquals("was", decode(pathFor("was"), dict))
    }

    @Test fun swipeThatNeverLeavesOneKeyFallsBackToThatLetter() {
        val tiny = listOf(
            floatArrayOf(100f, 150f), floatArrayOf(102f, 150f),
            floatArrayOf(104f, 150f), floatArrayOf(106f, 150f)
        )
        assertEquals("a", decode(tiny))
    }

    @Test fun capitalLabelsStillDecode() {
        val upper = layout.mapValues { (c, k) ->
            Key(c.uppercaseChar().toString(), k.code).apply { left = k.left; right = k.right; top = k.top; bottom = k.bottom }
        }
        assertEquals("was", decode(pathFor("was"), keys = upper))
    }

    // ---- double letters ----

    @Test fun loopOnAKeyBecomesADoubleLetter() {
        assertEquals("ass", decode(pathFor("as", loops = setOf(1))))
        assertEquals("waas", decode(pathFor("was", loops = setOf(1))))
        assertEquals("wass", decode(pathFor("was", loops = setOf(2))))
    }

    @Test fun straightSwipeHasNoDoubleLetters() {
        assertEquals("was", decode(pathFor("was")))
    }

    @Test fun loopInsideALongerWordIsDetected() {
        val withLoop = decode(pathFor("hello", loops = setOf(3)))!!
        val without = decode(pathFor("hello"))!!
        assertTrue(withLoop, withLoop.contains("ll"))
        assertFalse(without, without.contains("ll"))
    }

    @Test fun uTurnAtTheEndOfAWordIsNotADoubleLetter() {
        // "dog" turns around on o; a single U-turn is not a loop.
        val raw = decode(pathFor("dog"))!!
        assertFalse(raw, raw.contains("oo"))
        val wow = decode(pathFor("wow"))!!
        assertFalse(wow, wow.contains("oo"))
    }

    @Test fun loopedSwipeCanReachADoubleLetterDictionaryWord() {
        val dict = FakeDictionary(known = setOf("ass"))
        assertEquals("ass", decode(pathFor("as", loops = setOf(1)), dict))
    }

    @Test fun missingDoubleLetterIsRecoveredFromTheDictionary() {
        // No loop was drawn, but only the doubled spelling is a real word.
        assertEquals("ass", decode(pathFor("as"), FakeDictionary(known = setOf("ass"))))
    }

    @Test fun exactWordWinsOverDoubledVariant() {
        assertEquals("as", decode(pathFor("as"), FakeDictionary(known = setOf("as", "ass"))))
    }

    @Test fun doubledLetterVariantsAreGeneratedLeftToRightWithoutDuplicates() {
        val d = SlideDecoder(FakeDictionary())
        assertEquals(listOf("hhelo", "heelo", "hello", "heloo"), d.withOneDoubledLetter("helo"))
        assertEquals(listOf("aaa"), d.withOneDoubledLetter("aa"))
        assertEquals(listOf("aa1"), d.withOneDoubledLetter("a1"))
        assertTrue(d.withOneDoubledLetter("a").isEmpty())
        assertTrue(d.withOneDoubledLetter("a".repeat(17)).isEmpty())
    }

    // ---- ranking (now done internally by decode; tested end-to-end) ----

    @Test fun decodePutsTheSwipedWordFirst() {
        val decoder = SlideDecoder(FakeDictionary(known = setOf("was", "wad", "wax", "sad")))
        val path = pathFor("was")
        val result = decode(path, decoder)
        assertEquals("was", result)
    }

    @Test fun decodeHandlesTrivialInputs() {
        val decoder = SlideDecoder(FakeDictionary())
        assertEquals(null, decode(emptyList(), decoder))
        assertEquals(null, decode(pathFor("was"), decoder, emptyMap()))
    }

    @Test fun decodeHandlesWordsWithUnknownCharacters() {
        val decoder = SlideDecoder(FakeDictionary(known = setOf("was")))
        val result = decode(pathFor("was"), decoder)
        assertEquals("was", result)
    }

    // ---- resample ----

    @Test fun resampleSpreadsPointsEvenlyAlongTheStroke() {
        val decoder = SlideDecoder(FakeDictionary())
        val line = listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(100f, 0f))
        val r = decoder.resample(line, 3)
        assertEquals(3, r.size)
        assertEquals(0f, r[0][0], 0.01f)
        assertEquals(50f, r[1][0], 0.01f)
        assertEquals(100f, r[2][0], 0.01f)
    }

    @Test fun resampleEdgeCases() {
        val decoder = SlideDecoder(FakeDictionary())
        assertTrue(decoder.resample(emptyList(), 3).isEmpty())
        assertTrue(decoder.resample(listOf(floatArrayOf(1f, 1f)), 0).isEmpty())
        val single = decoder.resample(listOf(floatArrayOf(5f, 6f), floatArrayOf(9f, 9f)), 1)
        assertEquals(5f, single.single()[0], 0f)
        val still = decoder.resample(listOf(floatArrayOf(5f, 6f), floatArrayOf(5f, 6f)), 3)
        assertEquals(3, still.size)
        assertEquals(6f, still[2][1], 0f)
    }
}
