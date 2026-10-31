package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.exp

/**
 * Converts raw model scores into calibrated acceptance probabilities via a
 * bucketed histogram of historical accept/reject outcomes.
 *
 * Post-fix:
 *  - [recordShown] is now SEPARATE from [recordAcceptance]. Previously the
 *    pipeline called `recordPrediction(raw, false)` once per top-8 candidate
 *    per keystroke, inflating the "shown" denominator ~8x.
 *  - [calibrated] is now actually called by the ranking pipeline (was dead
 *    code in the prior version).
 *  - [clear] is exposed for the user-facing "Reset learned data" action.
 */
class ConfidenceCalibrator(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_confidence_calibrator", Context.MODE_PRIVATE)

    @Synchronized
    fun recordShown(rawScore: Float) {
        try {
            val b = bucket(rawScore)
            val shown = prefs.getInt(shownKey(b), 0) + 1
            prefs.edit().putInt(shownKey(b), shown).apply()
        } catch (e: Exception) {
            Log.w(TAG, "recordShown failed", e)
        }
    }

    @Synchronized
    fun recordAcceptance(rawScore: Float) {
        try {
            val b = bucket(rawScore)
            val accepted = prefs.getInt(acceptedKey(b), 0) + 1
            prefs.edit().putInt(acceptedKey(b), accepted).apply()
        } catch (e: Exception) {
            Log.w(TAG, "recordAcceptance failed", e)
        }
    }

    @Synchronized
    fun calibrated(rawScore: Float): Float {
        val b = bucket(rawScore)
        val shown = prefs.getInt(shownKey(b), 0)
        val accepted = prefs.getInt(acceptedKey(b), 0)
        val empirical = if (shown >= MIN_SAMPLES) accepted.toFloat() / shown else 0.5f
        val sig = sigmoid(rawScore)
        return (PRIOR_WEIGHT * sig + EMPIRICAL_WEIGHT * empirical).coerceIn(0f, 1f)
    }

    private fun bucket(raw: Float): Int {
        val sig = sigmoid(raw)
        return (sig * BUCKET_COUNT).toInt().coerceIn(0, BUCKET_COUNT - 1)
    }

    private fun shownKey(b: Int) = "b_${b}_shown"
    private fun acceptedKey(b: Int) = "b_${b}_accepted"

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).toFloat()

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "ConfidenceCalibrator"
        private const val BUCKET_COUNT = 20
        private const val MIN_SAMPLES = 5
        private const val PRIOR_WEIGHT = 0.35f
        private const val EMPIRICAL_WEIGHT = 0.65f
    }
}
