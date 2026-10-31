package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks how often suggestions at each sentence position (beginning /
 * middle / end) get accepted vs. shown.
 *
 * The intuition is that users often accept different *kinds* of suggestions
 * at different positions: at the start of a sentence they want a "starter"
 * word, at the end they want a closer, in the middle they want a content
 * word.  This scorer lets the ranker weight position-dependent features.
 *
 * Schema: per position bucket (0=beginning, 1=middle, 2=end), an integer
 * `shown` and `accepted` count are stored in a SharedPreferences file.
 */
class SentencePositionScorer(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_sentence_position", Context.MODE_PRIVATE)

    enum class Position { BEGINNING, MIDDLE, END }

    fun classify(composingLength: Int, sentenceProgress: Float): Position {
        // sentenceProgress is in [0, 1]: how far along the current sentence we are.
        return when {
            composingLength <= 1 && sentenceProgress < 0.15f -> Position.BEGINNING
            sentenceProgress > 0.85f -> Position.END
            else -> Position.MIDDLE
        }
    }

    @Synchronized
    fun recordShown(position: Position) {
        val key = shownKey(position)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    @Synchronized
    fun recordAcceptance(position: Position) {
        val key = acceptedKey(position)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /**
     * Empirical acceptance rate for [position] in [0, 1].  Returns 0.5 when
     * there is not yet enough data, so the ranker can ignore the feature.
     */
    @Synchronized
    fun acceptanceRate(position: Position): Float {
        val shown = prefs.getInt(shownKey(position), 0)
        val accepted = prefs.getInt(acceptedKey(position), 0)
        if (shown < MIN_SAMPLES) return 0.5f
        return (accepted.toFloat() / shown).coerceIn(0f, 1f)
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun shownKey(p: Position) = "p_${p.ordinal}_shown"
    private fun acceptedKey(p: Position) = "p_${p.ordinal}_accepted"

    companion object {
        private const val MIN_SAMPLES = 5
    }
}
