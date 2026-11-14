package com.noircipher.shade.analytics

import com.noircipher.shade.Key
import com.noircipher.shade.KeyKind
import com.noircipher.shade.TouchGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the personalized touch model: normalized per-key
 * bias accumulation, sample-count gating, sanity clamps, and the touch
 * remap used by the autocorrect precision pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchBiasModelTest {

    /** A 100x50 'k' key centered at (150, 125). */
    private fun key(label: String = "k", cx: Float = 150f, cy: Float = 125f, w: Float = 100f, h: Float = 50f): Key {
        val k = Key(label = label, kind = KeyKind.CHAR)
        k.left = cx - w / 2
        k.right = cx + w / 2
        k.top = cy - h / 2
        k.bottom = cy + h / 2
        return k
    }

    private fun model() = TouchBiasModel(RuntimeEnvironment.getApplication())

    @Test fun nonCharKeysAreIgnored() {
        val m = model()
        m.record(Key(label = "⇧", kind = KeyKind.FUNC), 0f, 0f)
        m.record(Key(label = " ", kind = KeyKind.SPACE), 0f, 0f)
        assertNull(m.normalizedBias("⇧"))
        assertNull(m.normalizedBias(" "))
    }

    @Test fun multiLetterLabelsAreIgnored() {
        val m = model()
        m.record(Key(label = "ab", kind = KeyKind.CHAR), 150f, 125f)
        assertNull(m.normalizedBias("ab"))
    }

    @Test fun needsMinSamplesBeforeRemap() {
        val m = model()
        val k = key()
        // MIN_REMAP_SAMPLES - 1 taps: no bias yet, remap is a no-op.
        for (i in 0 until TouchBiasModel.MIN_REMAP_SAMPLES - 1) {
            m.record(k, 160f, 125f)  // always 10px right of center
        }
        assertNull(m.normalizedBias("k"))
        val touch = TouchGeometry.KeyTouch(dx = 0.10f, dy = 0f)
        assertSame(touch, m.remap('k', touch))
    }

    @Test fun remapSubtractsLearnedBias() {
        val m = model()
        val k = key()  // width 100 -> dx 10px = 0.10 key units
        for (i in 0 until TouchBiasModel.MIN_REMAP_SAMPLES + 2) {
            m.record(k, 160f, 125f)
        }
        val bias = requireNotNull(m.normalizedBias("k"))
        assertEquals(0.10f, bias.avgDx, 0.001f)
        assertEquals(0f, bias.avgDy, 0.001f)

        val remapped = m.remap('k', TouchGeometry.KeyTouch(dx = 0.10f, dy = 0f))
        assertEquals("habitual offset must be re-centered", 0f, remapped.dx, 0.001f)
        assertEquals(0f, remapped.dy, 0.001f)
    }

    @Test fun normalizedBiasIsInKeyUnits() {
        val m = model()
        // 60px tall key: 15px down = 0.25 key-height units.
        val k = key(h = 60f)
        for (i in 0 until 10) {
            m.record(k, 150f, 140f)
        }
        val bias = requireNotNull(m.normalizedBias("k"))
        assertEquals(0.25f, bias.avgDy, 0.001f)
    }

    @Test fun absurdBiasIsRejected() {
        val m = model()
        // 0.4 key-widths right of center on every tap: noise, not a habit.
        val k = key()
        for (i in 0 until 20) {
            m.record(k, 190f, 125f)
        }
        assertNull(m.normalizedBias("k"))
        val touch = TouchGeometry.KeyTouch(dx = 0.4f, dy = 0f)
        assertSame(touch, m.remap('k', touch))
    }

    @Test fun remapAllAlignsTouchesToWordChars() {
        val m = model()
        val k = key("a", cx = 50f, cy = 125f)
        for (i in 0 until 10) m.record(k, 55f, 125f)  // +0.05 units right

        val touches = listOf(
            TouchGeometry.KeyTouch(0.05f, 0f),
            TouchGeometry.KeyTouch(0.05f, 0f)
        )
        val out = m.remapAll("ab", touches)
        assertEquals(2, out.size)
        assertEquals(0f, out[0].dx, 0.001f)     // 'a' has bias -> re-centered
        assertEquals(0.05f, out[1].dx, 0.001f)  // 'b' has no data -> unchanged
    }

    @Test fun remapAllEmptyTouchesIsIdentity() {
        val m = model()
        val touches = emptyList<TouchGeometry.KeyTouch>()
        assertSame(touches, m.remapAll("word", touches))
    }

    @Test fun pixelBiasStillWorksForHeatmap() {
        val m = model()
        val k = key()
        for (i in 0 until 5) {
            m.record(k, 160f, 125f)
        }
        val bias = requireNotNull(m.bias("k"))
        assertEquals(10f, bias.avgDx, 0.001f)  // pixels, unchanged behavior
        assertEquals(5, bias.count)
    }

    @Test fun clearRemovesEverything() {
        val m = model()
        val k = key()
        for (i in 0 until 10) m.record(k, 160f, 125f)
        m.clear()
        assertNull(m.normalizedBias("k"))
        assertNull(m.bias("k"))
    }

    @Test fun remapClampsExtremes() {
        val m = model()
        val k = key()
        for (i in 0 until 10) m.record(k, 163f, 125f)  // +0.13 units
        val out = m.remap('k', TouchGeometry.KeyTouch(dx = 0.9f, dy = -0.9f))
        // 0.9 - 0.13 = 0.77 -> clamped to 0.75
        assertTrue(out.dx <= 0.75f)
        assertTrue(out.dy >= -0.75f)
        assertFalse(out.dx > 0.76f)
    }
}
