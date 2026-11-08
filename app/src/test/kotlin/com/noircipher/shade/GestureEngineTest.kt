package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private var now = 1_000L
    private fun engine(keyWidth: Float = 100f) = GestureEngine(keyWidth) { now }

    /** A slow, steady swipe: 30 px every 100 ms (300 px/s, well under the recognition threshold). */
    private fun slowSwipe(e: GestureEngine, points: Int) {
        e.onDown(0f, 0f, 1000)
        for (i in 1 until points) e.addEventPoint(i * 30f, 0f, 1000 + i * 100)
    }

    @Test
    fun tapIsNotAGesture() {
        val e = engine()
        e.onDown(10f, 10f, 1000)
        assertFalse(e.isStartOfGesture())
        assertEquals(1, e.getLength())
    }

    @Test
    fun fastLongMoveIsDetectedAsGesture() {
        val e = engine()
        e.onDown(0f, 0f, 1000)
        e.addEventPoint(100f, 0f, 1010)   // 10 000 px/s: fast
        e.addEventPoint(200f, 0f, 1100)
        assertTrue(e.isStartOfGesture())
    }

    @Test
    fun pointsCloserThanSamplingDistanceAreDropped() {
        val e = engine(100f)              // sampling distance = 100/6 ~ 16 px
        e.onDown(0f, 0f, 1000)
        e.addEventPoint(5f, 0f, 1100)
        assertEquals(1, e.getLength())
        e.addEventPoint(40f, 0f, 1200)
        assertEquals(2, e.getLength())
    }

    @Test
    fun stalePointsAreIgnored() {
        val e = engine()
        e.onDown(0f, 0f, 1000)
        e.addEventPoint(50f, 0f, 1100)
        e.addEventPoint(100f, 0f, 1050)   // older than the last accepted point
        assertEquals(2, e.getLength())
    }

    @Test
    fun getPathReturnsSampledPointsInOrder() {
        val e = engine()
        slowSwipe(e, 4)
        val path = e.getPath()
        assertEquals(4, path.size)
        assertEquals(0f, path[0][0], 0f)
        assertEquals(90f, path[3][0], 0f)
    }

    @Test
    fun livePreviewPathGrowsWhileSwiping() {
        // Regression: move events used not to advance the incremental size, so the
        // live glide preview never had enough points.
        val e = engine()
        slowSwipe(e, 9)
        assertEquals(9, e.getLength())
        assertTrue(e.getIncrementalPath().size >= 6)
    }

    @Test
    fun setKeyWidthChangesSamplingDistance() {
        val e = engine(100f)
        e.onDown(0f, 0f, 1000)
        e.setKeyWidth(200f)               // sampling distance becomes ~33 px
        e.addEventPoint(30f, 0f, 1100)
        assertEquals(1, e.getLength())
    }

    @Test
    fun recognitionIsThrottled() {
        val e = engine()
        assertFalse(e.hasRecognitionTimePast(1000, 1000))
        assertFalse(e.hasRecognitionTimePast(1100, 1000))
        assertTrue(e.hasRecognitionTimePast(1101, 1000))
    }

    @Test
    fun trailIsVisibleWhileSwipingAndExpiresAfterLinger() {
        val e = engine()
        slowSwipe(e, 3)
        assertTrue(e.hasTrail())
        now += 800
        assertTrue(e.hasTrail())          // exactly at the linger limit
        now += 1
        assertFalse(e.hasTrail())         // nothing left to redraw -> no endless invalidate()
    }

    @Test
    fun addTrailRestartsFadeFromRelease() {
        val e = engine()
        slowSwipe(e, 3)
        now += 700
        e.addTrail()                      // finger lifted
        now += 700
        assertTrue(e.hasTrail())
        now += 101
        assertFalse(e.hasTrail())
    }

    @Test
    fun newStrokeClearsPreviousTrailAndPath() {
        val e = engine()
        slowSwipe(e, 5)
        e.onDown(500f, 500f, 5000)
        assertEquals(1, e.getLength())
        assertFalse(e.hasTrail())         // a single point is not a trail
    }
}
