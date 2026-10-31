package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Adapts the online ranker's learning rate based on the user's overall
 * acceptance rate.
 *
 * When the user accepts suggestions frequently (high acceptance), the ranker
 * should learn faster — the user trusts it, so update weights more boldly.
 * When the user rarely accepts, the ranker should learn cautiously — its
 * current weights are clearly mis-calibrated, so each individual update
 * should be small to avoid overshooting.
 *
 * Schema: two integer counters (`shown` and `accepted`) persisted in a
 * SharedPreferences file.
 */
class AcceptanceRateAdapter(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_acceptance_rate", Context.MODE_PRIVATE)

    @Synchronized
    fun recordShown(now: Long = System.currentTimeMillis()) {
        val shown = prefs.getInt(KEY_SHOWN, 0) + 1
        prefs.edit().putInt(KEY_SHOWN, shown).putLong(KEY_LAST_UPDATE, now).apply()
    }

    @Synchronized
    fun recordAccepted(now: Long = System.currentTimeMillis()) {
        val accepted = prefs.getInt(KEY_ACCEPTED, 0) + 1
        prefs.edit().putInt(KEY_ACCEPTED, accepted).putLong(KEY_LAST_UPDATE, now).apply()
    }

    /**
     * Acceptance rate in [0, 1].  Returns 0.5 when there is not yet enough
     * data (so the ranker's default learning rate is used).
     */
    @Synchronized
    fun acceptanceRate(): Float {
        val shown = prefs.getInt(KEY_SHOWN, 0)
        val accepted = prefs.getInt(KEY_ACCEPTED, 0)
        if (shown < MIN_SAMPLES) return 0.5f
        return (accepted.toFloat() / shown).coerceIn(0f, 1f)
    }

    /**
     * Learning-rate multiplier for the online ranker.
     * - acceptanceRate >= 0.6 → 1.5x (fast learning)
     * - acceptanceRate <= 0.2 → 0.5x (cautious)
     * - otherwise → 1.0x (default)
     */
    fun learningRateMultiplier(): Float {
        val rate = acceptanceRate()
        return when {
            rate >= 0.6f -> 1.5f
            rate <= 0.2f -> 0.5f
            else -> 1.0f
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SHOWN = "shown"
        private const val KEY_ACCEPTED = "accepted"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val MIN_SAMPLES = 10
    }
}
