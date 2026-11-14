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
        e.setKeyWidth(200f)               // sampling distance becomes 200/8 = 25 px
        e.addEventPoint(20f, 0f, 1100)    // 20 px < 25 px: dropped
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
        now += 500
        assertTrue(e.hasTrail())          // exactly at the linger limit
        now += 1
        assertFalse(e.hasTrail())         // nothing left to redraw -> no endless invalidate()
    }

    @Test
    fun addTrailRestartsFadeFromRelease() {
        val e = engine()
        slowSwipe(e, 3)
        now += 100
        e.addTrail()                      // finger lifted
        now += 200
        assertTrue(e.hasTrail())          // inside the 220 ms fade
        now += 21
        assertFalse(e.hasTrail())         // fade over -> no endless invalidate()
    }

    @Test
    fun newStrokeClearsPreviousTrailAndPath() {
        val e = engine()
        slowSwipe(e, 5)
        e.onDown(500f, 500f, 5000)
        assertEquals(1, e.getLength())
        assertFalse(e.hasTrail())         // a single point is not a trail
    }

    // ---- finish(): the last stretch of the swipe -------------------------------------------

    @Test
    fun finishAppendsTheFinalPositionThatSamplingDropped() {
        val e = engine(100f)              // sampling distance = 100/6 ~ 16 px
        e.onDown(0f, 0f, 1000)
        e.addEventPoint(200f, 0f, 1100)   // kept
        e.addEventPoint(205f, 0f, 1110)   // 5 px further: dropped by sampling
        assertEquals(2, e.getLength())
        e.finish(205f, 0f)
        assertEquals(3, e.getLength())
        assertEquals(205f, e.getPath().last()[0], 0f)
    }

    @Test
    fun finishIgnoresAPositionOnTopOfTheLastPoint() {
        val e = engine(100f)
        e.onDown(0f, 0f, 1000)
        e.addEventPoint(200f, 0f, 1100)
        e.finish(200f, 0f)
        assertEquals(2, e.getLength())
    }

    @Test
    fun finishOnAnEmptyEngineDoesNothing() {
        val e = engine(100f)
        e.finish(50f, 50f)
        assertEquals(0, e.getLength())
    }
}
