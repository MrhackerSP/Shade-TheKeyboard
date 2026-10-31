package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.exp

/**
 * Online logistic-regression ranker trainer.
 *
 * Runs entirely on-device — "online" refers to online-learning, NOT
 * online-network. Persists learned weights to local SharedPreferences only.
 *
 * Post-fix:
 *  - L2 regularization on every step (prevents unbounded weight drift).
 *  - Schema version tag on persisted weights.
 *  - [score] returns sigmoid(z) in [0, 1].
 *  - [clear] is exposed for the user-facing "Reset learned data" action.
 */
class OnlineRankerTrainer(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_online_ranker", Context.MODE_PRIVATE)
    private val weights = FloatArray(RankingFeatureExtractor.FEATURE_SIZE)
    private var bias = 0f

    /** Multiplier applied to the per-step learning rate (see [setLearningRateMultiplier]). */
    @Volatile
    private var learningRateMultiplier: Float = 1.0f

    /** Cached RNG for exploration (lazily seeded per-call so we don't keep state across config changes). */
    private val explorationRng = java.util.Random()

    init {
        val savedVersion = prefs.getInt(SCHEMA_VERSION_KEY, -1)
        if (savedVersion == SCHEMA_VERSION) {
            val saved = prefs.getString(WEIGHTS_KEY, null)
            if (saved != null) {
                try {
                    saved.split(",").mapIndexed { i, v ->
                        if (i < weights.size) weights[i] = v.toFloat()
                    }
                    bias = prefs.getFloat(BIAS_KEY, 0f)
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed saved weights; resetting to zero", e)
                    weights.fill(0f)
                    bias = 0f
                }
            }
        } else {
            Log.i(TAG, "Schema version mismatch (saved=$savedVersion, current=$SCHEMA_VERSION); resetting")
            weights.fill(0f)
            bias = 0f
        }
    }

    @Synchronized
    fun adapt(positive: FloatArray, negatives: List<FloatArray>, learningRate: Float = DEFAULT_LR) {
        require(positive.size == weights.size) { "Positive feature size mismatch" }

        val effectiveLr = (learningRate * learningRateMultiplier).coerceIn(MIN_LR, MAX_LR)

        val posScore = scoreRaw(positive)
        val gradPos = 1f - sigmoid(posScore)
        for (i in weights.indices) {
            weights[i] = weights[i] * (1f - effectiveLr * L2_LAMBDA) * DECAY_FACTOR +
                         effectiveLr * gradPos * positive[i]
        }
        bias = bias * (1f - effectiveLr * L2_LAMBDA) * DECAY_FACTOR + effectiveLr * gradPos

        for (neg in negatives.take(MAX_NEGATIVES)) {
            if (neg.size != weights.size) continue
            val negScore = scoreRaw(neg)
            val gradNeg = -sigmoid(negScore)
            for (i in weights.indices) {
                weights[i] = weights[i] * (1f - effectiveLr * L2_LAMBDA) * DECAY_FACTOR +
                             effectiveLr * NEG_WEIGHT * gradNeg * neg[i]
            }
            bias = bias * (1f - effectiveLr * L2_LAMBDA) * DECAY_FACTOR +
                   effectiveLr * NEG_WEIGHT * gradNeg
        }
        persist()
    }

    @Synchronized
    fun score(features: FloatArray): Float {
        if (features.size != weights.size) return 0f
        return sigmoid(scoreRaw(features))
    }

    private fun scoreRaw(features: FloatArray): Float {
        var z = bias
        for (i in weights.indices) z += weights[i] * features[i]
        return z
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).toFloat()

    private fun persist() {
        try {
            prefs.edit()
                .putString(WEIGHTS_KEY, weights.joinToString(","))
                .putFloat(BIAS_KEY, bias)
                .putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist online ranker weights", e)
        }
    }

    /**
     * Whether the ranker should explore (i.e. surface a non-greedy suggestion
     * to gather training signal).  ~5% of calls return true.
     *
     * The exploration rate is small enough not to annoy the user, but provides
     * a steady trickle of off-policy feedback so weights don't get stuck.
     */
    @Synchronized
    fun shouldExplore(): Boolean = explorationRng.nextFloat() < EXPLORATION_RATE

    /**
     * Sets the multiplier applied to the per-step learning rate.
     * Driven by [AcceptanceRateAdapter] — when the user accepts suggestions
     * frequently, the multiplier is > 1 so the ranker learns faster; when
     * the user rarely accepts, it's < 1 so each individual update is small.
     */
    fun setLearningRateMultiplier(multiplier: Float) {
        learningRateMultiplier = multiplier.coerceIn(0.1f, 5.0f)
    }

    @Synchronized
    fun clear() {
        weights.fill(0f)
        bias = 0f
        learningRateMultiplier = 1.0f
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "OnlineRankerTrainer"
        private const val DEFAULT_LR = 0.03f
        private const val MIN_LR = 0.001f
        private const val MAX_LR = 0.3f
        private const val L2_LAMBDA = 1e-4f
        private const val MAX_NEGATIVES = 3
        private const val NEG_WEIGHT = 0.5f
        private const val SCHEMA_VERSION = 1
        private const val WEIGHTS_KEY = "weights"
        private const val BIAS_KEY = "bias"
        private const val SCHEMA_VERSION_KEY = "schema_version"
        private const val DECAY_FACTOR = 0.999f
        private const val EXPLORATION_RATE = 0.05f
    }
}
