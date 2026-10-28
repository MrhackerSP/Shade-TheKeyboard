package com.noircipher.shade

import java.util.ArrayDeque

/**
 * Ring buffer of the last N touch points recorded as the user typed.
 *
 * One entry per character. Cleared on word boundaries (space, enter,
 * punctuation). The spatial scorer uses these to figure out where the
 * user's finger actually landed, not where the key center is.
 *
 * Coordinates are in the KeyboardView's pixel space so they can be
 * compared directly against key bounds.
 */
class TouchTrace(private val capacity: Int = 16) {

    private val buf = ArrayDeque<FloatArray>(capacity)

    fun record(x: Float, y: Float) {
        if (buf.size >= capacity) buf.pollFirst()
        buf.offerLast(floatArrayOf(x, y))
    }

    /** The most recent [count] points, oldest first. */
    fun last(count: Int): List<FloatArray> {
        if (count <= 0 || buf.isEmpty()) return emptyList()
        val n = minOf(count, buf.size)
        return buf.toList().subList(buf.size - n, buf.size)
    }

    fun clear() = buf.clear()

    fun size(): Int = buf.size
}
