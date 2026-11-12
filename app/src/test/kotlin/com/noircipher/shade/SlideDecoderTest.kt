package com.noircipher.shade

import com.noircipher.shade.slide.GlideLexicon
import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.slide.SlideDictionary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Behaviour of the glide decoder. The expected values were checked against a line-by-line Python
 * mirror of the decoder, and the decoder's accuracy was measured on simulated finger paths over the
 * full dictionary (see the commit notes); these tests pin the behaviours that matter.
 */
class SlideDecoderTest {

    /** Tiny stand-in for the real dictionary. Priors are 0..1, 1 = most common. */
    private class FakeDictionary(
        bundled: Map<String, Float> = emptyMap(),
        user: Map<String, Float> = emptyMap(),
        private val known: Set<String> = emptySet()
    ) : SlideDictionary {
        private val bundledWords = bundled.keys
        private val userWords = user.keys
        private val bundledLex = GlideLexicon.build(bundled.keys.toList(), bundled.values.toFloatArray())
        private val userLex = GlideLexicon.build(user.keys.toList(), user.values.toFloatArray())
        override fun contains(word: String) = word in known || word in bundledWords || word in userWords
        override fun glideLexicon() = bundledLex
        override fun glideUserLexicon() = userLex
    }

    /**
     * QWERTY with [kw] x [kh] keys; row 2 is offset by half a key, row 3 by one and a half.
     * [digits] adds a number row above and [punct] comma/period keys below (they must be ignored);
     * [skip] leaves letters out.
     */
    private fun layoutOf(
        kw: Float = 100f, kh: Float = 100f,
        digits: Boolean = false, punct: Boolean = false, skip: String = ""
    ): Map<Char, Key> = buildMap {
        val rows = listOf(
            Triple("qwertyuiop", 0f, 0f),
            Triple("asdfghjkl", kw / 2, kh),
            Triple("zxcvbnm", kw * 1.5f, kh * 2)
        )
        for ((letters, offset, rowTop) in rows) {
            letters.forEachIndexed { i, c ->
                if (c in skip) return@forEachIndexed
                put(c, Key(c.toString(), c.code).apply {
                    left = offset + i * kw; right = left + kw
                    top = rowTop; bottom = rowTop + kh
                })
            }
        }
        if (digits) "1234567890".forEachIndexed { i, c ->
            put(c, Key(c.toString(), c.code).apply { left = i * kw; right = left + kw; top = -kh; bottom = 0f })
        }
        if (punct) {
            put(',', Key(",", ','.code).apply { left = kw * 1.5f; right = left + kw; top = kh * 3; bottom = kh * 4 })
            put('.', Key(".", '.'.code).apply { left = kw * 7.5f; right = left + kw; top = kh * 3; bottom = kh * 4 })
        }
    }

    private val layout = layoutOf()

    private fun center(keys: Map<Char, Key>, c: Char) =
        floatArrayOf(keys.getValue(c).centerX, keys.getValue(c).centerY)

    /**
     * Straight segments through the centres of [word]'s keys, one point every [step] px. Keys whose
     * index is in [loops] get a small circle drawn on them (the gesture for a double letter).
     * [shift] moves the corner points, as a finger cutting corners would.
     */
    private fun pathFor(
        word: String,
        keys: Map<Char, Key> = layout,
        loops: Set<Int> = emptySet(),
        step: Float = 9f,
        shift: List<Pair<Float, Float>>? = null
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val cs = word.mapIndexed { i, c ->
            val m = center(keys, c)
            val s = shift?.getOrNull(i)
            if (s == null) m else floatArrayOf(m[0] + s.first, m[1] + s.second)
        }
        for (i in 0 until cs.size - 1) {
            val a = cs[i]; val b = cs[i + 1]
            val n = maxOf(1, (hypot(b[0] - a[0], b[1] - a[1]) / step).toInt())
            for (k in 0 until n) out.add(floatArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n))
            if ((i + 1) in loops) out.addAll(loopAround(keys, word[i + 1]))
        }
        out.add(cs.last())
        if ((word.length - 1) in loops) out.addAll(loopAround(keys, word.last()))
        return out
    }

    private fun loopAround(keys: Map<Char, Key>, c: Char, radius: Float = 25f, points: Int = 8): List<FloatArray> {
        val m = center(keys, c)
        return (0 until points).map { k ->
            val angle = 2.0 * Math.PI * k / points
            floatArrayOf(m[0] + (radius * cos(angle)).toFloat(), m[1] + (radius * sin(angle)).toFloat())
        }
    }

    private fun decode(
        path: List<FloatArray>,
        dict: SlideDictionary = FakeDictionary(),
        keys: Map<Char, Key> = layout,
        context: Map<String, Float> = emptyMap()
    ) = runBlocking { SlideDecoder(dict).decode(path, keys, context) }

    private fun candidates(path: List<FloatArray>, dict: SlideDictionary, max: Int) =
        runBlocking { SlideDecoder(dict).decodeCandidates(path, layout, max) }

    private val hellos = mapOf("hello" to 0.8f, "help" to 0.85f, "held" to 0.7f, "hero" to 0.75f, "hell" to 0.6f, "hole" to 0.7f)

    // ---- basics ----

    @Test fun decodesAWordFromTheKeysItPassesOver() {
        assertEquals("hello", decode(pathFor("helo"), FakeDictionary(hellos)))
    }

    @Test fun emptyOrSinglePointPathGivesNothing() {
        assertNull(decode(emptyList(), FakeDictionary(hellos)))
        assertNull(decode(listOf(floatArrayOf(10f, 10f)), FakeDictionary(hellos)))
    }

    @Test fun emptyDictionaryGivesNothingRatherThanRawKeys() {
        assertNull(decode(pathFor("was")))
        assertNull(decode(pathFor("qazwsxedcrfv"), FakeDictionary()))
    }

    @Test fun neverReturnsAStringThatIsNotAWord() {
        val dict = FakeDictionary(mapOf("the" to 0.9f, "and" to 0.8f, "you" to 0.7f))
        // Starts at q and ends at v: no word of the dictionary fits, and "qazwsxedcrfv" must not come out.
        assertNull(decode(pathFor("qazwsxedcrfv"), dict))
    }

    @Test fun layoutWithoutLettersGivesNothing() {
        val symbols = buildMap { put('1', Key("1", '1'.code).apply { left = 0f; right = 100f; top = 0f; bottom = 100f }) }
        assertNull(decode(pathFor("helo"), FakeDictionary(hellos), symbols))
    }

    // ---- ranking ----

    @Test fun commonWordBeatsRareWordWithTheSamePath() {
        // "as" and "ass" draw the same path; the more common one must win. (The old decoder added
        // ln(frequency) to a cost that is minimised, so it preferred the RARE word.)
        val dict = FakeDictionary(mapOf("as" to 0.6f, "ass" to 0.55f))
        assertEquals("as", decode(pathFor("as"), dict))
        val reversed = FakeDictionary(mapOf("as" to 0.55f, "ass" to 0.6f))
        assertEquals("ass", decode(pathFor("as"), reversed))
    }

    @Test fun aLoopOnAKeyFavoursTheDoubledSpelling() {
        val dict = FakeDictionary(mapOf("as" to 0.6f, "ass" to 0.55f))
        assertEquals("ass", decode(pathFor("as", loops = setOf(1)), dict))
    }

    @Test fun firstAndLastLetterMustMatchTheEndsOfTheSwipe() {
        val dict = FakeDictionary(mapOf("cat" to 0.1f, "dog" to 0.99f, "cot" to 0.2f))
        assertEquals("cat", decode(pathFor("cat"), dict))
    }

    @Test fun longerWordWinsWhenTheSwipeContinuesPastTheShorterOne() {
        val dict = FakeDictionary(mapOf("the" to 0.9f, "there" to 0.6f, "three" to 0.5f))
        assertEquals("there", decode(pathFor("there"), dict))
        assertEquals("the", decode(pathFor("the"), dict))
    }

    @Test fun contextBreaksTiesBetweenSimilarWords() {
        val dict = FakeDictionary(mapOf("as" to 0.6f, "ass" to 0.55f))
        assertEquals("as", decode(pathFor("as"), dict))
        assertEquals("ass", decode(pathFor("as"), dict, context = mapOf("ass" to 1f)))
    }

    @Test fun learnedWordsAreDecoded() {
        val dict = FakeDictionary(bundled = mapOf("the" to 0.9f, "and" to 0.8f), user = mapOf("zork" to 0.7f))
        assertEquals("zork", decode(pathFor("zork"), dict))
    }

    @Test fun candidatesAreDistinctBestFirstAndLimited() {
        val dict = FakeDictionary(hellos)
        val list = candidates(pathFor("helo"), dict, 3)
        assertEquals("hello", list.first())
        assertTrue(list.size <= 3)
        assertEquals(list.size, list.toSet().size)
        assertEquals(list.first(), decode(pathFor("helo"), dict))
    }

    // ---- sloppy input ----

    @Test fun aCornerCutAtEveryLetterStillFindsTheWord() {
        // Each letter is hit up to 35 px (a third of a key) off its centre; the old decoder needed
        // every letter within 40% of a key of the path to even consider the word.
        val dict = FakeDictionary(mapOf("wonder" to 0.5f, "wander" to 0.5f, "ponder" to 0.5f, "wonders" to 0.4f, "winder" to 0.3f))
        val shift = listOf(0f to 0f, 10f to -35f, -25f to 30f, 30f to -20f, -15f to 35f, 0f to 0f)
        assertEquals("wonder", decode(pathFor("wonder", shift = shift), dict))
    }

    @Test fun aSwipeStartingOneKeyAwayFromTheFirstLetterStillDecodes() {
        // Starts over 'e' (120 px right of 'w'); only the relaxed second pass reaches 'w'.
        val path = pathFor("word").mapIndexed { i, p -> if (i == 0) floatArrayOf(p[0] + 120f, p[1]) else p }
        assertEquals("word", decode(path, FakeDictionary(mapOf("word" to 0.7f, "ford" to 0.6f))))
    }

    // ---- layout handling ----

    @Test fun digitAndPunctuationKeysAreIgnored() {
        val keys = layoutOf(digits = true, punct = true)
        assertEquals("hello", decode(pathFor("helo", keys), FakeDictionary(hellos), keys))
    }

    @Test fun capitalLabelsStillDecode() {
        val keys = layout.mapValues { (c, k) -> Key(c.uppercaseChar().toString(), c.code).apply { left = k.left; right = k.right; top = k.top; bottom = k.bottom } }
        assertEquals("hello", decode(pathFor("helo", keys), FakeDictionary(hellos), keys))
    }

    @Test fun wordsNeedingAMissingLetterAreSkipped() {
        val keys = layoutOf(skip = "q")
        val dict = FakeDictionary(mapOf("hello" to 0.8f, "quote" to 0.9f))
        assertEquals("hello", decode(pathFor("helo", keys), dict, keys))
    }

    @Test fun keySizeIsDerivedFromTheLayout() {
        val big = layoutOf(200f, 200f)
        assertEquals("hello", decode(pathFor("helo", big, step = 18f), FakeDictionary(hellos), big))
        val tall = layoutOf(100f, 150f)
        assertEquals("hello", decode(pathFor("helo", tall, step = 9f), FakeDictionary(hellos), tall))
    }

    // ---- tiny paths ----

    @Test fun aSwipeThatNeverLeavesOneKeyIsOnlyAOneLetterWord() {
        val inA = listOf(floatArrayOf(100f, 150f), floatArrayOf(102f, 150f), floatArrayOf(101f, 152f), floatArrayOf(103f, 149f))
        assertEquals("a", decode(inA, FakeDictionary(hellos, known = setOf("a"))))
        assertNull(decode(inA, FakeDictionary(hellos)))
    }

    // ---- robustness ----

    @Test fun nonFinitePointsAreIgnored() {
        val path = pathFor("helo").toMutableList()
        path.add(3, floatArrayOf(Float.NaN, 10f))
        assertEquals("hello", decode(path, FakeDictionary(hellos)))
    }

    @Test fun aLargeLexiconStaysFast() {
        val rnd = java.util.Random(7)
        val big = LinkedHashMap<String, Float>()
        while (big.size < 40_000) {
            val len = 3 + rnd.nextInt(8)
            big[String(CharArray(len) { 'a' + rnd.nextInt(26) })] = rnd.nextFloat() * 0.5f
        }
        big["hello"] = 1f
        val dict = FakeDictionary(big)
        val t0 = System.nanoTime()
        val out = decode(pathFor("helo"), dict)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("hello", out)
        assertTrue("decode took $ms ms", ms < 5_000)
    }
}
