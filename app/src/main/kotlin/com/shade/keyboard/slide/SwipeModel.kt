package com.shade.keyboard.slide

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.tanh

/**
 * Fitted reranker for glide decoding, trained on simulated swipes
 * (see `scripts/train_swipe_model.py` for the trainer and the feature parity rules).
 *
 * Stage 2 of [SlideDecoder] already produces a calibrated hand-tuned score. The
 * trained model adds a correction on top: each surviving candidate's raw stage-2
 * feature vector is scored by the model (higher = more likely the word the finger
 * meant), and `blend x score` is subtracted from the hand-tuned penalty (lower
 * total score = better candidate).
 *
 * Model classes; the trainer emits whichever wins on a word-disjoint hold-out set:
 *  - version 1: a linear logit (`weights` + `bias`);
 *  - version 2: a one-hidden-layer network on raw features (legacy, still loadable);
 *  - version 3: a one-hidden-layer network on standardised features - the JSON
 *    carries per-feature mean/std (`norm`), inputs are z-scored and clipped to
 *    +/-3.5 standard deviations before the hidden layer. Standardisation plus
 *    clipping keeps tanh units in their responsive range, which is what lets
 *    the network out-rank the linear form.
 *
 * Every model may also carry two frozen language-model tables:
 *  - `bigrams`, 676 natural log-probabilities (row-major prev*26+next);
 *  - `trigrams`, 17,576 natural log-probabilities (row-major prev2*676+prev1*26+next,
 *    interpolated with a back-off to the bigram model when fitted).
 * The decoder feeds the candidate's mean bigram and trigram log-probabilities
 * as features - a language-model signal that separates plausible letter
 * sequences from lookalike paths ("value" vs "vampire").
 *
 * Credit: the idea of fitting a correction on top of a hand-tuned glide score follows
 * FUTO Keyboard's trained glide typing (https://github.com/futo-futo/keyboard). This
 * reranker, its feature vector and its weights are Shade's own, fitted by
 * scripts/train_swipe_model.py on simulated swipes.
 *
 * With no bundled asset, or a corrupt one, [load] returns null and the decoder
 * behaves exactly as before - the model can only refine, never break, decoding.
 */
class SwipeModel private constructor(
    /** Version 1: linear weights (empty for versions 2/3). */
    private val weights: FloatArray,
    /** Version 1: linear bias (unused for versions 2/3). */
    private val bias: Float,
    /** Versions 2/3: hidden layer weights, row-major units x features (empty for version 1). */
    private val hiddenW: FloatArray,
    /** Versions 2/3: hidden layer bias. */
    private val hiddenB: FloatArray,
    /** Versions 2/3: output weights (empty for version 1). */
    private val outW: FloatArray,
    /** Versions 2/3: output bias. */
    private val outB: Float,
    /** Version 3: per-feature means (empty otherwise). */
    private val normMean: FloatArray,
    /** Version 3: per-feature standard deviations, all > 0 (empty otherwise). */
    private val normStd: FloatArray,
    /** Frozen letter-bigram log-probabilities (prev*26+next), null when absent. */
    private val bigrams: FloatArray?,
    /** Frozen letter-trigram log-probabilities (prev2*676+prev1*26+next), null when absent. */
    private val trigrams: FloatArray?,
    /** How strongly the fitted score corrects the hand-tuned score. */
    val blend: Float
) {
    /**
     * Penalty added to a candidate's hand-tuned score.
     * Returns 0 for a feature vector of unexpected size (defensive; never breaks ranking).
     */
    fun penalty(features: FloatArray): Float {
        if (features.size != FEATURE_NAMES.size) return 0f
        val logit = if (hiddenW.isEmpty()) {
            var dot = 0f
            for (i in weights.indices) dot += weights[i] * features[i]
            bias + dot
        } else {
            val f = if (normMean.size == features.size) FloatArray(features.size) {
                val z = (features[it] - normMean[it]) / normStd[it]
                when {
                    z > Z_CLAMP -> Z_CLAMP
                    z < -Z_CLAMP -> -Z_CLAMP
                    else -> z
                }
            } else features
            val units = hiddenB.size
            var out = outB
            for (u in 0 until units) {
                var acc = hiddenB[u]
                val row = u * features.size
                for (i in f.indices) acc += hiddenW[row + i] * f[i]
                out += outW[u] * tanh(acc)
            }
            out
        }
        return -blend * logit
    }

    /**
     * Mean letter-bigram log-probability of [word] under the frozen table shipped
     * in the model JSON (higher = a more plausible English letter sequence).
     * Mirrors the trainer's bigram_feature exactly: non a-z letters are skipped,
     * fewer than two letters score 0, and the sum is divided by the transition
     * count. Returns 0 when the model carries no table.
     */
    fun bigramScore(word: String): Float {
        val table = bigrams ?: return 0f
        var prev = -1
        var sum = 0f
        var n = 0
        for (raw in word) {
            val ch = raw.lowercaseChar()
            if (ch < 'a' || ch > 'z') continue
            val c = ch - 'a'
            if (prev >= 0) {
                sum += table[prev * 26 + c]
                n++
            }
            prev = c
        }
        return if (n > 0) sum / n else 0f
    }

    /**
     * Mean letter-trigram log-probability of [word] under the frozen table
     * shipped in the model JSON. Mirrors the trainer's trigram_feature exactly:
     * non a-z letters are skipped, fewer than three letters score 0, and the
     * sum is divided by the transition count (letter count - 2). Returns 0
     * when the model carries no table.
     */
    fun trigramScore(word: String): Float {
        val table = trigrams ?: return 0f
        var prev2 = -1
        var prev1 = -1
        var sum = 0f
        var n = 0
        for (ch in word) {
            if (ch < 'a' || ch > 'z') continue
            val c = ch - 'a'
            if (prev2 >= 0) {
                sum += table[prev2 * 676 + prev1 * 26 + c]
                n++
            }
            prev2 = prev1
            prev1 = c
        }
        return if (n > 0) sum / n else 0f
    }

    companion object {
        /**
         * Raw stage-2 features fed to the model, in order. The trainer must
         * compute these exactly like [SlideDecoder] does - both sides keep the
         * same names and the same order.
         */
        val FEATURE_NAMES = listOf(
            "shape", "adh", "corner", "cdist", "vowel", "edit",
            "lenratio", "prior", "letters",
            "shape2", "adh2", "editlen", "startd", "endd", "bigram",
            "align", "trigram"
        )
        /** Bigram table size: 26 x 26 natural log-probabilities, row-major. */
        const val BIGRAM_TABLE_SIZE = 26 * 26
        /** Trigram table size: 26 x 26 x 26 natural log-probabilities, row-major. */
        const val TRIGRAM_TABLE_SIZE = 26 * 26 * 26
        /** Version-3 z-score clamp, mirrored from the trainer's CLIP_Z. */
        private const val Z_CLAMP = 3.5f
        const val ASSET = "swipe_model.json"

        @Volatile
        private var cached: SwipeModel? = null

        /** Parses a model description; null when the JSON is unusable. */
        fun parse(json: String): SwipeModel? {
            return try {
                val obj = JSONObject(json)
                val version = obj.optInt("version", 0)
                val names = obj.optJSONArray("features") ?: return null
                if (names.length() != FEATURE_NAMES.size) return null
                for (i in 0 until names.length()) {
                    if (names.optString(i) != FEATURE_NAMES[i]) return null
                }
                val blend = obj.optDouble("blend", 0.0).toFloat()
                if (!blend.isFinite() || blend < 0f) return null
                val bigrams = parseBigrams(obj)
                if (version == 1) {
                    val w = obj.optJSONArray("weights") ?: return null
                    if (w.length() != FEATURE_NAMES.size) return null
                    val weights = FloatArray(FEATURE_NAMES.size)
                    for (i in weights.indices) {
                        val v = w.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        weights[i] = v
                    }
                    val bias = obj.optDouble("bias", 0.0).toFloat()
                    return SwipeModel(weights, if (bias.isFinite()) bias else 0f,
                        FloatArray(0), FloatArray(0), FloatArray(0), 0f,
                        FloatArray(0), FloatArray(0), bigrams, parseTrigrams(obj), blend)
                }
                if (version == 2 || version == 3) {
                    val hidden = obj.optJSONObject("hidden") ?: return null
                    val output = obj.optJSONObject("output") ?: return null
                    val units = hidden.optInt("units", -1)
                    if (units <= 0 || units > 256) return null
                    val hw = hidden.optJSONArray("w") ?: return null
                    val hb = hidden.optJSONArray("b") ?: return null
                    val ow = output.optJSONArray("w") ?: return null
                    if (hw.length() != units * FEATURE_NAMES.size ||
                        hb.length() != units || ow.length() != units
                    ) return null
                    val hiddenW = FloatArray(units * FEATURE_NAMES.size)
                    for (i in hiddenW.indices) {
                        val v = hw.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        hiddenW[i] = v
                    }
                    val hiddenB = FloatArray(units)
                    for (i in hiddenB.indices) {
                        val v = hb.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        hiddenB[i] = v
                    }
                    val outW = FloatArray(units)
                    for (i in outW.indices) {
                        val v = ow.getDouble(i).toFloat()
                        if (!v.isFinite()) return null
                        outW[i] = v
                    }
                    val outB = output.optDouble("b", 0.0).toFloat()
                    if (!outB.isFinite()) return null
                    var normMean = FloatArray(0)
                    var normStd = FloatArray(0)
                    if (version == 3) {
                        val norm = obj.optJSONObject("norm") ?: return null
                        val meanArr = norm.optJSONArray("mean") ?: return null
                        val stdArr = norm.optJSONArray("std") ?: return null
                        if (meanArr.length() != FEATURE_NAMES.size ||
                            stdArr.length() != FEATURE_NAMES.size
                        ) return null
                        normMean = FloatArray(FEATURE_NAMES.size)
                        normStd = FloatArray(FEATURE_NAMES.size)
                        for (i in FEATURE_NAMES.indices) {
                            val mu = meanArr.getDouble(i).toFloat()
                            val sd = stdArr.getDouble(i).toFloat()
                            if (!mu.isFinite() || !sd.isFinite() || sd <= 1e-6f) return null
                            normMean[i] = mu
                            normStd[i] = sd
                        }
                    }
                    return SwipeModel(FloatArray(0), 0f, hiddenW, hiddenB, outW, outB,
                        normMean, normStd, bigrams, parseTrigrams(obj), blend)
                }
                null
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * Optional frozen bigram table; null when absent. A malformed table
         * (wrong length, non-numeric or non-finite entries) throws so the
         * model is rejected rather than silently loaded without its table.
         */
        private fun parseBigrams(obj: JSONObject): FloatArray? {
            val arr = obj.optJSONArray("bigrams") ?: return null
            val table = FloatArray(BIGRAM_TABLE_SIZE)
            for (i in table.indices) {
                val v = arr.getDouble(i).toFloat()
                if (!v.isFinite()) throw JSONException("non-finite bigram score at $i")
                table[i] = v
            }
            return table
        }

        /**
         * Optional frozen trigram table; null when absent. A malformed table
         * throws so the model is rejected rather than silently loaded without
         * its table.
         */
        private fun parseTrigrams(obj: JSONObject): FloatArray? {
            val arr = obj.optJSONArray("trigrams") ?: return null
            val table = FloatArray(TRIGRAM_TABLE_SIZE)
            for (i in table.indices) {
                val v = arr.getDouble(i).toFloat()
                if (!v.isFinite()) throw JSONException("non-finite trigram score at $i")
                table[i] = v
            }
            return table
        }

        /**
         * Loads the bundled model once per process; null when unavailable
         * (the decoder then falls back to the unaided hand-tuned ranking).
         */
        fun load(context: Context): SwipeModel? {
            cached?.let { return it }
            return try {
                val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
                parse(bytes.decodeToString())?.also { cached = it }
            } catch (t: Throwable) {
                null
            }
        }

        /** Drops the cached model (used by tests). */
        fun resetForTests() {
            cached = null
        }
    }
}
