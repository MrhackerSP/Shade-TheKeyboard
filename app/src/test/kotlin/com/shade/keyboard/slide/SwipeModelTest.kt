package com.shade.keyboard.slide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and scoring of the trained glide reranker model. */
class SwipeModelTest {

    private fun featureListJson(): String =
        SwipeModel.FEATURE_NAMES.joinToString(",", "[", "]") { "\"$it\"" }

    private fun json(
        version: Int = 1,
        features: String = featureListJson(),
        weights: String = "[1.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]",
        bias: String = "0.1",
        blend: String = "2.0"
    ) = """{"version":$version,"features":$features,"weights":$weights,"bias":$bias,"blend":$blend}"""

    /** A version-3 model with the given normalisation, hidden and output layers. */
    private fun neuralJson(
        mean: String = List(SwipeModel.FEATURE_NAMES.size) { "0.0" }.joinToString(","),
        std: String = List(SwipeModel.FEATURE_NAMES.size) { "1.0" }.joinToString(","),
        // 2 hidden units x 17 features, row-major (the loader validates the size)
        hiddenW: String = List(2 * SwipeModel.FEATURE_NAMES.size) { "0.1" }.joinToString(","),
        hiddenB: String = "0.0, 0.0",
        outW: String = "2.0, -1.0",
        outB: String = "0.0",
        blend: String = "2.0"
    ) = """{"version":3,"features":${featureListJson()},""" +
        """"hidden":{"w":[$hiddenW],"b":[$hiddenB],"units":2},""" +
        """"output":{"w":[$outW],"b":$outB},""" +
        """"norm":{"mean":[$mean],"std":[$std]},"blend":$blend}"""

    private fun jsonWithBigrams(inner: String): String {
        val table = List(SwipeModel.BIGRAM_TABLE_SIZE) { "0.0" }.toTypedArray()
        table[0 * 26 + 1] = "-0.6931"  // a->b = log(0.5)
        table[1 * 26 + 2] = "-1.3863"  // b->c = log(0.25)
        val tableJson = "[" + table.joinToString(",") + "]"
        return inner.replace("\"blend\"", "\"bigrams\":$tableJson,\"blend\"")
    }

    private fun jsonWithTrigrams(inner: String): String {
        val table = List(SwipeModel.TRIGRAM_TABLE_SIZE) { "0.0" }.toTypedArray()
        table[0 * 676 + 1 * 26 + 2] = "-1.0986"   // a,b->c = log(1/3)
        table[1 * 676 + 2 * 26 + 3] = "-0.4055"   // b,c->d = log(2/3)
        val tableJson = "[" + table.joinToString(",") + "]"
        return inner.replace("\"blend\"", "\"trigrams\":$tableJson,\"blend\"")
    }

    @Test
    fun parsesAValidLinearModel() {
        val model = SwipeModel.parse(json())
        assertNotNull(model)
        assertEquals(2.0f, model!!.blend, 1e-6f)
    }

    @Test
    fun penaltyFollowsTheLinearLogit() {
        val model = SwipeModel.parse(json())!!
        // logit = bias + sum(w * f) = 0.1 + (1 - 2 + 0.5) = -0.4; penalty = -blend * logit = 0.8
        val f = FloatArray(SwipeModel.FEATURE_NAMES.size) { 1f }
        assertEquals(0.8f, model.penalty(f), 1e-4f)
    }

    @Test
    fun aHigherLogitMeansALowerPenalty() {
        // Positive-dominant weights so larger features give a LARGER logit:
        // logit(strong) = 0.1 + 3*(1+2+0.5) = 10.6, logit(weak) = 1.85; since
        // penalty = -blend * logit, penalty(strong) = -21.2 < penalty(weak) = -3.7.
        val model = SwipeModel.parse(
            json(weights = "[1.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]")
        )!!
        val strong = FloatArray(SwipeModel.FEATURE_NAMES.size) { 3f }
        val weak = FloatArray(SwipeModel.FEATURE_NAMES.size) { 0.5f }
        assertTrue(model.penalty(strong) < model.penalty(weak))
    }

    @Test
    fun unexpectedFeatureCountIsIgnoredNotABreak() {
        val model = SwipeModel.parse(json())!!
        assertEquals(0f, model.penalty(FloatArray(3)), 1e-6f)
        assertEquals(0f, model.penalty(FloatArray(0)), 1e-6f)
    }

    @Test
    fun parsesAValidLegacyNeuralModel() {
        // hidden units = 2; all hidden weights 0.1 -> 17 x 0.1 = 1.7 pre-activation per unit
        val n = SwipeModel.FEATURE_NAMES.size
        val hw = List(2 * n) { "0.1" }.joinToString(",")
        val model = SwipeModel.parse(
            """{"version":2,"features":${featureListJson()},""" +
                """"hidden":{"w":[$hw],"b":[0.0,0.0],"units":2},""" +
                """"output":{"w":[2.0,-1.0],"b":0.0},"blend":2.0}"""
        )
        assertNotNull(model)
        // logit = 2*tanh(1.7) - tanh(1.7) = tanh(1.7); penalty = -2 * tanh(1.7)
        val f = FloatArray(n) { 1f }
        assertEquals(-2.0f * kotlin.math.tanh(1.7f), model!!.penalty(f), 1e-4f)
    }

    @Test
    fun parsesAValidStandardisedNeuralModel() {
        val model = SwipeModel.parse(neuralJson())
        assertNotNull(model)
        val f = FloatArray(SwipeModel.FEATURE_NAMES.size) { 1f }
        // identity norm -> 17 x 0.1 = 1.7 pre-activation per unit, logit = tanh(1.7)
        assertEquals(-2.0f * kotlin.math.tanh(1.7f), model!!.penalty(f), 1e-4f)
    }

    @Test
    fun standardisationIsAppliedBeforeTheHiddenLayer() {
        // mean 1, std 2: a feature of 3.0 standardises to 1.0 -> same logit as the
        // identity-norm net with raw features of 1.0.
        val n = SwipeModel.FEATURE_NAMES.size
        val mean = List(n) { "1.0" }.joinToString(",")
        val std = List(n) { "2.0" }.joinToString(",")
        val shifted = SwipeModel.parse(neuralJson(mean = mean, std = std))!!
        val identity = SwipeModel.parse(neuralJson())!!
        val raw = FloatArray(n) { 3f }
        assertEquals(identity.penalty(FloatArray(n) { 1f }), shifted.penalty(raw), 1e-4f)
    }

    @Test
    fun neuralPenaltyFollowsTheSignOfTheLogit() {
        val n = SwipeModel.FEATURE_NAMES.size
        val hw = List(2 * n) { "0.0" }.joinToString(",")
        val mk = { outW: String, b: String ->
            SwipeModel.parse(neuralJson(hiddenW = hw, hiddenB = "0.5, 0.5", outW = outW, outB = b))!!
        }
        val f = FloatArray(n) { 1f }
        // tanh(0.5) > 0 -> negative weights give a positive penalty (worse score)
        assertTrue(mk("-1.0", "0.0").penalty(f) > 0f)
        // positive weights give a negative penalty (better score)
        assertTrue(mk("1.0", "0.0").penalty(f) < 0f)
    }

    @Test
    fun bigramScoreMirrorsTheTrainer() {
        val model = SwipeModel.parse(jsonWithBigrams(neuralJson()))!!
        // a->b = log(0.5), b->c = log(0.25); "abc" = mean of the two transitions
        assertEquals((-0.6931f + -1.3863f) / 2f, model.bigramScore("abc"), 1e-4f)
        assertEquals(-0.6931f, model.bigramScore("ab"), 1e-4f)
        assertEquals(0f, model.bigramScore("a"), 1e-4f)
        assertEquals(0f, model.bigramScore(""), 1e-4f)
        // apostrophes are skipped, matching the trainer's bigram_feature
        assertEquals(-0.6931f, model.bigramScore("a'b"), 1e-4f)
    }

    @Test
    fun bigramScoreIsZeroWithoutATable() {
        val model = SwipeModel.parse(json())!!
        assertEquals(0f, model.bigramScore("abc"), 1e-6f)
    }

    @Test
    fun trigramScoreMirrorsTheTrainer() {
        val model = SwipeModel.parse(jsonWithTrigrams(neuralJson()))!!
        // a,b->c = log(1/3); "abc" has exactly one trigram transition
        assertEquals(-1.0986f, model.trigramScore("abc"), 1e-4f)
        // "abcd" = mean of a,b->c and b,c->d
        assertEquals((-1.0986f + -0.4055f) / 2f, model.trigramScore("abcd"), 1e-4f)
        // fewer than three letters score 0
        assertEquals(0f, model.trigramScore("ab"), 1e-6f)
        assertEquals(0f, model.trigramScore("a"), 1e-6f)
        assertEquals(0f, model.trigramScore(""), 1e-6f)
        // non a-z letters are skipped, matching the trainer's trigram_feature
        assertEquals(-1.0986f, model.trigramScore("a'bc"), 1e-4f)
    }

    @Test
    fun trigramScoreIsZeroWithoutATable() {
        val model = SwipeModel.parse(json())!!
        assertEquals(0f, model.trigramScore("abc"), 1e-6f)
    }

    @Test
    fun standardisedFeaturesAreClampedBeforeTheHiddenLayer() {
        // identity norm: a feature of 100 z-scores to 100 but is clamped to 3.5,
        // so the penalty equals the one for a raw feature of exactly 3.5.
        val model = SwipeModel.parse(neuralJson())!!
        val n = SwipeModel.FEATURE_NAMES.size
        assertEquals(model.penalty(FloatArray(n) { 3.5f }),
            model.penalty(FloatArray(n) { 100f }), 1e-5f)
        assertEquals(model.penalty(FloatArray(n) { -3.5f }),
            model.penalty(FloatArray(n) { -100f }), 1e-5f)
    }

    @Test
    fun rejectsBadModels() {
        assertNull(SwipeModel.parse("not json at all"))
        assertNull(SwipeModel.parse(json(version = 2)))
        assertNull(SwipeModel.parse("""{"version":4,"features":${featureListJson()}}"""))
        assertNull(SwipeModel.parse(json(weights = "[1.0, -2.0]")))
        assertNull(SwipeModel.parse(json(features = """["a","b"]""")))
        assertNull(SwipeModel.parse(json(blend = "-1.0")))
        assertNull(SwipeModel.parse("""{"version":1}"""))
        // version 2 shape errors
        val n = SwipeModel.FEATURE_NAMES.size
        val hwBad = List(n) { "0.1" }.joinToString(",")
        assertNull(SwipeModel.parse(
            """{"version":2,"features":${featureListJson()},""" +
                """"hidden":{"w":[$hwBad],"b":[0.0,0.0],"units":2},""" +
                """"output":{"w":[1.0,1.0],"b":0.0},"blend":1.0}"""
        )) // hidden.w size != units * features
        assertNull(SwipeModel.parse(
            """{"version":2,"features":${featureListJson()},""" +
                """"hidden":{"w":[$hwBad],"b":[0.0],"units":1},"blend":1.0}"""
        )) // missing output object
        // version 3: norm is required and validated
        assertNull(SwipeModel.parse(
            """{"version":3,"features":${featureListJson()},""" +
                """"hidden":{"w":[$hwBad],"b":[0.0],"units":1},"output":{"w":[1.0],"b":0.0},"blend":1.0}"""
        )) // missing norm
        assertNull(SwipeModel.parse(neuralJson(std = List(n) { "0.0" }.joinToString(",")))) // zero std
        assertNull(SwipeModel.parse(neuralJson(std = List(n - 1) { "1.0" }.joinToString(",")))) // short std
        // a malformed bigram table is rejected rather than half-loaded
        assertNull(SwipeModel.parse(
            jsonWithBigrams(neuralJson()).replace("\"bigrams\":[", "\"bigrams\":[1.0,2.0,\"")
        ))
        // a malformed trigram table is rejected rather than half-loaded
        assertNull(SwipeModel.parse(
            jsonWithTrigrams(neuralJson()).replace("\"trigrams\":[", "\"trigrams\":[1.0,\"")
        ))
    }

    @Test
    fun featureNamesMatchTheTrainer() {
        // The trainer (scripts/train_swipe_model.py) emits features in this exact
        // order; SlideDecoder fills the vector by index, so the names are a contract.
        assertEquals(
            listOf("shape", "adh", "corner", "cdist", "vowel", "edit",
                "lenratio", "prior", "letters",
                "shape2", "adh2", "editlen", "startd", "endd", "bigram",
                "align", "trigram"),
            SwipeModel.FEATURE_NAMES
        )
    }
}
