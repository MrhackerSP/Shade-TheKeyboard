package com.noircipher.shade.engine

import android.os.SystemClock
import java.util.ArrayDeque

/**
 * #19 — Typing speed adapter.
 *
 * Tracks keystroke intervals on the main thread (single-threaded, no
 * synchronization needed). Classifies the user as fast / slow / normal,
 * and produces a confidence-threshold adjustment: when typing fast (likely
 * error-prone), raise the autocorrect threshold a little; when typing
 * deliberately, lower it a little.
 */
object TypingSpeedAdapter {

    private const val WINDOW = 8
    private const val FAST_INTERVAL_MS = 90L
    private const val SLOW_INTERVAL_MS = 350L

    private val intervals = ArrayDeque<Long>()
    private var lastKeystrokeNs: Long = 0L

    fun onKeystroke() {
        val now = SystemClock.uptimeMillis()
        if (lastKeystrokeNs != 0L) {
            val delta = now - lastKeystrokeNs
            if (delta in 1..5000) {
                intervals.addLast(delta)
                while (intervals.size > WINDOW) intervals.removeFirst()
            }
        }
        lastKeystrokeNs = now
    }

    private fun averageIntervalMs(): Long {
        if (intervals.isEmpty()) return 0L
        var sum = 0L
        for (v in intervals) sum += v
        return sum / intervals.size
    }

    fun isFastTyping(): Boolean {
        val avg = averageIntervalMs()
        return avg != 0L && avg <= FAST_INTERVAL_MS
    }

    fun isSlowTyping(): Boolean {
        val avg = averageIntervalMs()
        return avg >= SLOW_INTERVAL_MS
    }

    /**
     * Positive = stricter (raise threshold). Negative = more permissive.
     * Range roughly [-0.05, +0.05].
     */
    fun confidenceThresholdAdjustment(): Float {
        val avg = averageIntervalMs()
        if (avg == 0L) return 0f
        return when {
            avg <= FAST_INTERVAL_MS -> 0.05f   // raise threshold
            avg >= SLOW_INTERVAL_MS -> -0.05f  // lower threshold
            else -> {
                // Linear interpolation in [-0.05, +0.05].
                val t = (avg - FAST_INTERVAL_MS).toFloat() /
                    (SLOW_INTERVAL_MS - FAST_INTERVAL_MS).toFloat()
                (0.05f * (1f - t) - 0.05f * t).coerceIn(-0.05f, 0.05f)
            }
        }
    }

    fun reset() {
        intervals.clear()
        lastKeystrokeNs = 0L
    }
}
