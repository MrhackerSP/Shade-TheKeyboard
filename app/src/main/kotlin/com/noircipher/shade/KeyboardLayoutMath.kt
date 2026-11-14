package com.noircipher.shade

/**
 * Pure geometry over the laid-out key grid, extracted from [KeyboardView] so
 * the math is unit-testable on the JVM and reusable without instantiating the
 * view. No drawing state involved; [blendRects] mutates [Key] rects in place,
 * exactly as the view's inline code did before the extraction.
 */
internal object KeyboardLayoutMath {

    /** Flat [left, top, right, bottom] per key, row-major, same order as the rows list. */
    fun snapshotRects(rows: List<List<Key>>): FloatArray {
        val out = FloatArray(rows.sumOf { it.size } * 4)
        var i = 0
        for (row in rows) for (k in row) {
            out[i] = k.left
            out[i + 1] = k.top
            out[i + 2] = k.right
            out[i + 3] = k.bottom
            i += 4
        }
        return out
    }

    /** Blends every key's rect from snapshot [a] to [b] at fraction [t].
     *  Returns false if the row shape changed since the snapshots were taken,
     *  so the caller can fall back to a full layout pass. */
    fun blendRects(rows: List<List<Key>>, a: FloatArray, b: FloatArray, t: Float, pad: Int): Boolean {
        if (a.size != b.size) return false
        var i = 0
        for (row in rows) for (k in row) {
            if (i + 3 >= a.size) return false
            k.left = a[i] + (b[i] - a[i]) * t
            k.top = a[i + 1] + (b[i + 1] - a[i + 1]) * t
            k.right = a[i + 2] + (b[i + 2] - a[i + 2]) * t
            k.bottom = a[i + 3] + (b[i + 3] - a[i + 3]) * t
            k.updateInvalidateRect(pad)
            i += 4
        }
        return true
    }

    /** Nearest-key hit test: closest row by vertical span, then closest key by horizontal span.
     *  Manual loops, no allocation — this runs on every touch event. */
    fun hitKey(rows: List<List<Key>>, x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        // Find the row whose vertical span is closest to y — manual loop, no allocation.
        var bestRow = -1
        var bestRowDist = Float.MAX_VALUE
        for (i in rows.indices) {
            val k = rows[i][0]
            val d = when {
                y < k.top -> k.top - y
                y > k.bottom -> y - k.bottom
                else -> 0f
            }
            if (d < bestRowDist) {
                bestRowDist = d
                bestRow = i
            }
        }
        if (bestRow < 0) return null
        // Find the key in that row whose horizontal span is closest to x.
        var bestKey: Key? = null
        var bestKeyDist = Float.MAX_VALUE
        for (k in rows[bestRow]) {
            if (k.kind == KeyKind.SPACER) continue
            val d = when {
                x < k.left -> k.left - x
                x > k.right -> x - k.right
                else -> 0f
            }
            if (d < bestKeyDist) {
                bestKeyDist = d
                bestKey = k
            }
        }
        return bestKey
    }
}
