package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * #10 — Sentence-position scorer.
 *
 * Tracks acceptance rates by sentence position (start, middle, end) so we
 * can learn which suggestions work best where. Persists counts in
 * SharedPreferences so they survive across sessions.
 *
 * Position is derived from the trailing context words. If the last word
 * ends with `.`, `!`, or `?` (or there is no context at all), we're at
 * START. Otherwise MIDDLE. We don't currently track END explicitly — it's
 * folded into MIDDLE.
 */
class SentencePositionScorer(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_sentence_position", Context.MODE_PRIVATE)

    enum class Position { START, MIDDLE, END }

    fun currentPosition(contextWords: List<String>): Position {
        if (contextWords.isEmpty()) return Position.START
        val last = contextWords.last()
        return when {
            last.endsWith('.') || last.endsWith('!') || last.endsWith('?') -> Position.START
            else -> Position.MIDDLE
        }
    }

    fun recordAcceptance(position: Position) {
        val shown = prefs.getInt(keyShown(position), 0)
        if (shown == 0) return  // Don't pollute acceptance stats before any shown.
        val accepted = prefs.getInt(keyAccepted(position), 0) + 1
        prefs.edit().putInt(keyAccepted(position), accepted).apply()
    }

    fun recordShown(position: Position) {
        val shown = prefs.getInt(keyShown(position), 0) + 1
        prefs.edit().putInt(keyShown(position), shown).apply()
    }

    fun acceptanceRate(position: Position): Float {
        val shown = prefs.getInt(keyShown(position), 0)
        if (shown == 0) return 0f
        val accepted = prefs.getInt(keyAccepted(position), 0)
        return (accepted.toFloat() / shown.toFloat()).coerceIn(0f, 1f)
    }

    private fun keyShown(p: Position) = "shown_${p.name}"
    private fun keyAccepted(p: Position) = "accepted_${p.name}"
}
