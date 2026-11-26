package com.shade.keyboard

import com.shade.keyboard.slide.GlideLexicon
import com.shade.keyboard.slide.SlideDecoder
import com.shade.keyboard.slide.SlideDictionary
import com.shade.keyboard.slide.SwipeModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainedSwipeRerankTest {

    private class FakeDictionary(
        private val bundled: Map<String, Float>
    ) : SlideDictionary {
        private val lex = GlideLexicon.build(bundled.keys.toList(), bundled.values.toFloatArray())
        override fun contains(word: String) = bundled.containsKey(word)
        override fun glideLexicon() = lex
    }

    private fun layout(): Map<Char, Key> = buildMap {
        val row = "asd"
        row.forEachIndexed { i, c ->
            put(c, Key(c.toString(), c.code).apply {
                left = i * 100f; right = left + 100f
                top = 0f; bottom = 100f
            })
        }
    }

    private fun outAndBackPath(): List<FloatArray> {
        val a = floatArrayOf(0f, 50f)
        val d = floatArrayOf(200f, 50f)
        val s = floatArrayOf(100f, 50f)
        val out = ArrayList<FloatArray>()
        for (leg in listOf(a to d, d to s)) {
            val (p, q) = leg
            val n = ((q[0] - p[0]).let { if (it < 0) -it else it } / 10f).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                out.add(floatArrayOf(p[0] + (q[0] - p[0]) * k / n, p[1] + (q[1] - p[1]) * k / n))
            }
        }
        out.add(s)
        return out
    }

    private fun modelWith(letterWeight: Float, blend: Float = 1f): SwipeModel? {
        val weights = FloatArray(SwipeModel.FEATURE_NAMES.size)
        weights[SwipeModel.FEATURE_NAMES.indexOf("letters")] = letterWeight
        val w = weights.joinToString(", ") { v -> "$v " }
        return SwipeModel.parse(
            "{ \"version\":1, \"features\":${featureListJson()}, " +
            "\"weights\":[$w], \"bias\":0.0, \"blend\":$blend}"
        )
    }

    private fun featureListJson(): String =
        SwipeModel.FEATURE_NAMES.joinToString(",", "[", "]") { "\"$it\"" }

    private fun candidates(model: SwipeModel?): List<String> = runBlocking {
        SlideDecoder(FakeDictionary(mapOf("as" to 0.5f, "ads" to 0.5f)), model)
            .decodeCandidates(outAndBackPath(), layout(), 2)
    }

    @Test
    fun zeroWeightModelKeepsTheHandRanking() {
        val plain = candidates(null)
        assertEquals(listOf("ads", "as"), plain)
        val zero = modelWith(0f, blend = 5f)!!
        assertEquals(plain, candidates(zero))
    }

    @Test
    fun extremeLetterWeightIsClampedAndCannotOverruleHandRanking() {
        val shortModel = modelWith(-1000f)!!
        assertEquals("ads", candidates(shortModel).first())
        val longModel = modelWith(1000f)!!
        assertEquals("ads", candidates(longModel).first())
    }

    @Test
    fun bothCandidatesSurviveTheStrictGates() {
        val out = candidates(null)
        assertEquals(2, out.size)
        assertTrue(out.contains("as"))
        assertTrue(out.contains("ads"))
    }
}
