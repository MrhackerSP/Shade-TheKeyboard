package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure key-grid geometry extracted from KeyboardView.
 * (blendRects is not covered here: it calls Key.updateInvalidateRect, which
 * instantiates android.graphics.Rect — a Robolectric-only path.)
 */
class KeyboardLayoutMathTest {

    private fun key(l: Float, t: Float, r: Float, b: Float, label: String = "a", kind: KeyKind = KeyKind.CHAR): Key =
        Key(label, kind = kind).also { k ->
            k.left = l; k.top = t; k.right = r; k.bottom = b
        }

    @Test
    fun snapshotIsRowMajorLeftTopRightBottom() {
        val a = key(0f, 0f, 10f, 10f)
        val b = key(10f, 0f, 20f, 10f)
        val snap = KeyboardLayoutMath.snapshotRects(listOf(listOf(a, b)))
        assertEquals(8, snap.size)
        assertEquals(0f, snap[0], 0f)
        assertEquals(0f, snap[1], 0f)
        assertEquals(10f, snap[2], 0f)
        assertEquals(10f, snap[3], 0f)
        assertEquals(10f, snap[4], 0f)
        assertEquals(20f, snap[6], 0f)
    }

    @Test
    fun hitInsideKeyReturnsIt() {
        val a = key(0f, 0f, 10f, 10f, label = "a")
        val b = key(10f, 0f, 20f, 10f, label = "b")
        assertEquals("b", KeyboardLayoutMath.hitKey(listOf(listOf(a, b)), 15f, 5f)?.label)
    }

    @Test
    fun hitSnapsToNearestRowAndKey() {
        val top = key(0f, 0f, 10f, 10f, label = "top")
        val bottom = key(0f, 100f, 10f, 110f, label = "bottom")
        val rows = listOf(listOf(top), listOf(bottom))
        // Far above the grid still snaps to the closest row.
        assertEquals("top", KeyboardLayoutMath.hitKey(rows, 5f, -40f)?.label)
        // Between rows snaps to whichever span is closer.
        assertEquals("top", KeyboardLayoutMath.hitKey(rows, 5f, 55f)?.label)
        assertEquals("bottom", KeyboardLayoutMath.hitKey(rows, 5f, 56f)?.label)
    }

    @Test
    fun hitSkipsSpacers() {
        val spacer = key(0f, 0f, 10f, 10f, kind = KeyKind.SPACER)
        val real = key(10f, 0f, 20f, 10f, label = "real")
        assertEquals("real", KeyboardLayoutMath.hitKey(listOf(listOf(spacer, real)), 5f, 5f)?.label)
    }

    @Test
    fun hitOnEmptyRowsIsNull() {
        assertNull(KeyboardLayoutMath.hitKey(emptyList(), 0f, 0f))
    }

    @Test
    fun blendMismatchesReturnFalseAndChangeNothing() {
        val a = key(0f, 0f, 10f, 10f)
        val rows = listOf(listOf(a))
        val s1 = KeyboardLayoutMath.snapshotRects(rows)
        val s2 = FloatArray(s1.size + 4)
        assertFalse(KeyboardLayoutMath.blendRects(rows, s1, s2, 0.5f, 0))
        assertEquals(0f, a.left, 0f)
        assertEquals(10f, a.right, 0f)
    }

    // blendRects' happy path is not covered here: it calls
    // Key.updateInvalidateRect, which instantiates android.graphics.Rect —
    // a device / Robolectric-only path.
}
