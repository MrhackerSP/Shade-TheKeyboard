package com.noircipher.shade.engine

import android.os.SystemClock

/**
 * Adapts autocorrect aggressiveness to the user's current typing speed.
 *
 * Fast typing: the user knows what they want; autocorrect should be
 *   conservative (higher confidence threshold) so it doesn't override
 *   deliberately-typed words.
 * Slow typing: the user is picking letters carefully; typos are more likely,
 *   so autocorrect can be more aggressive (lower threshold).
 *
 * The adapter tracks keystroke timestamps and exposes a multiplier for the
 * autocorrect confidence threshold (e.g. 1.0 = no adjustment, >1.0 = stricter
 * autocorrect, <1.0 = more aggressive).
 */
class TypingSpeedAdapter {

    private val recentKeystrokes = LongArray(WINDOW_SIZE)
    private var nextIndex = 0
    private var filled = 0

    /** Called on every keystroke (or composing-text update). */
    fun onKeystroke(now: Long = SystemClock.elapsedRealtime()) {
        recentKeystrokes[nextIndex] = now
        nextIndex = (nextIndex + 1) % WINDOW_SIZE
        if (filled < WINDOW_SIZE) filled++
    }

    /** Median inter-keystroke interval in ms, or 0 when not enough data. */
    fun medianIntervalMs(): Long {
        if (filled < 2) return 0L
        val intervals = ArrayList<Long>(filled - 1)
        // Walk in insertion order to compute consecutive intervals.
        val startIdx = (nextIndex - filled + WINDOW_SIZE) % WINDOW_SIZE
        var prev = recentKeystrokes[startIdx]
        for (i in 1 until filled) {
            val idx = (startIdx + i) % WINDOW_SIZE
            val cur = recentKeystrokes[idx]
            intervals.add(cur - prev)
            prev = cur
        }
        intervals.sort()
        return intervals[intervals.size / 2]
    }

    /**
     * Multiplier for the autocorrect confidence threshold.
     * - Fast typers (< 110ms median) → 1.20 (stricter).
     * - Normal typers (110-250ms) → 1.00.
     * - Slow typers (> 250ms) → 0.85 (more aggressive).
     */
    fun confidenceThresholdAdjustment(): Float {
        val median = medianIntervalMs()
        if (median <= 0L) return 1.0f
        return when {
            median < 110L -> 1.20f
            median > 250L -> 0.85f
            else -> 1.0f
        }.coerceIn(0.7f, 1.3f)
    }

    fun reset() {
        nextIndex = 0
        filled = 0
    }

    companion object {
        private const val WINDOW_SIZE = 12
    }
}
