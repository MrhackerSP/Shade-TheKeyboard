package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * #16 — Acceptance rate adapter.
 *
 * Tracks global suggestion acceptance rates (shown vs accepted) and turns
 * the running rate into a learning-rate multiplier for the online ranker.
 * High acceptance → bump the learning rate (the ranker's signal is good).
 * Low acceptance → back off (signal is noisy).
 *
 * Persisted in SharedPreferences so the rate estimate is stable across
 * sessions.
 */
class AcceptanceRateAdapter(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_acceptance_rate", Context.MODE_PRIVATE)

    @Synchronized
    fun recordShown() {
        val shown = prefs.getInt(KEY_SHOWN, 0) + 1
        prefs.edit().putInt(KEY_SHOWN, shown).apply()
    }

    @Synchronized
    fun recordAccepted() {
        val accepted = prefs.getInt(KEY_ACCEPTED, 0) + 1
        prefs.edit().putInt(KEY_ACCEPTED, accepted).apply()
    }

    @Synchronized
    fun acceptanceRate(): Float {
        val shown = prefs.getInt(KEY_SHOWN, 0)
        if (shown == 0) return 0.5f  // neutral prior
        val accepted = prefs.getInt(KEY_ACCEPTED, 0)
        return (accepted.toFloat() / shown.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Multiplier in roughly [0.5, 2.0]. A 50% acceptance rate maps to 1.0.
     */
    fun learningRateMultiplier(): Float {
        val rate = acceptanceRate()
        // Linear: 0% -> 0.5x, 50% -> 1.0x, 100% -> 1.5x
        return (0.5f + rate).coerceIn(0.5f, 2.0f)
    }

    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SHOWN = "shown"
        private const val KEY_ACCEPTED = "accepted"
    }
}
