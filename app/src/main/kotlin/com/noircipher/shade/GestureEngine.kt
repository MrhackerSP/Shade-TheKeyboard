package com.noircipher.shade

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Gesture (glide) typing sampler and trail renderer.
 *
 * Usage:
 *   val gesture = GestureEngine(keyWidth)
 *   // On down:
 *   gesture.onDown(x, y, elapsedMs)
 *   // On move:
 *   gesture.addEventPoint(x, y, elapsedMs)
 *   // On up:
 *   val path = gesture.getPath()
 *   // Draw trail:
 *   gesture.drawTrail(canvas, paint)
 */
class GestureEngine(
    private var keyWidth: Float,
    /** Time source; injectable so trail expiry can be unit-tested. */
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    private var detectFastMoveSpeedThreshold = 1.5f * keyWidth // pixels/sec
    private var staticTimeThresholdAfterFastTyping = 350 // ms
    private var dynamicThresholdDecayDuration = 450 // ms
    private var dynamicTimeThresholdFrom = 300 // ms
    private var dynamicTimeThresholdTo = 20 // ms
    private var dynamicDistanceThresholdFrom = 6.0f * keyWidth // pixels
    private var dynamicDistanceThresholdTo = 0.35f * keyWidth // pixels
    private var samplingMinimumDistance = keyWidth / 6.0f // pixels
    private var recognitionMinimumTime = 100 // ms
    private var recognitionSpeedThreshold = 5.5f * keyWidth // pixels/sec

    fun setKeyWidth(w: Float) {
        keyWidth = w
        detectFastMoveSpeedThreshold = 1.5f * w
        dynamicDistanceThresholdFrom = 6.0f * w
        dynamicDistanceThresholdTo = 0.35f * w
        samplingMinimumDistance = w / 6.0f
        recognitionSpeedThreshold = 5.5f * w
    }

    // ---- Sampling state ----
    private val eventTimes = ArrayList<Int>()
    private val xCoords = ArrayList<Int>()
    private val yCoords = ArrayList<Int>()
    private var detectFastMoveTime = 0
    private var detectFastMoveX = 0
    private var detectFastMoveY = 0
    private var afterFastTyping = false
    private var lastMajorEventTime = 0
    private var lastMajorEventX = 0
    private var lastMajorEventY = 0
    private var incrementalRecognitionSize = 0

    // ---- Trail rendering state ----
    private val trailX = ArrayList<Float>()
    private val trailY = ArrayList<Float>()
    private val trailTimes = ArrayList<Long>()
    private var trailColor = 0x80FFFFFF.toInt()
    private var trailReleaseTime = 0L  // 0 = still drawing; >0 = finger lifted, fading out

    // Trail visual parameters
    private var trailStartWidth = 10f    // px at the finger (thick end)
    private var trailEndWidth = 1f      // px at the tail (thin end)
    private var trailLingerDuration = 800L   // ms the trail stays visible after the last point before fading
    private var trailFadeDuration = 800L     // ms for the whole-trail fade-out after release
    private val trailMaxPoints = 120        // cap memory; old points are dropped

    // Reusable buffers for smooth drawing
    private val smoothPath = Path()
    private val pathMeasure = PathMeasure()
    private val posBuffer = FloatArray(2)
    private val tanBuffer = FloatArray(2)

    fun setTrailColor(color: Int) { trailColor = color }
    fun setTrailWidths(start: Float, end: Float) { trailStartWidth = start; trailEndWidth = end }

    /** Reset state for a new gesture stroke. */
    fun onDown(x: Float, y: Float, elapsedTime: Int) {
        reset()
        addEventPoint(x.toInt(), y.toInt(), elapsedTime, true)
    }

    /** Add a move event point. Returns true if the point is on the keyboard. */
    fun addEventPoint(x: Float, y: Float, elapsedTime: Int): Boolean {
        return addEventPoint(x.toInt(), y.toInt(), elapsedTime, true)
    }

    private fun addEventPoint(x: Int, y: Int, time: Int, isMajor: Boolean): Boolean {
        val size = eventTimes.size
        if (size == 0) {
            // First point (down event)
            appendPoint(x, y, time)
            updateMajorEvent(x, y, time)
        } else {
            val distance = detectFastMove(x, y, time)
            // Sample: only add points that are far enough from the last one
            if (distance > samplingMinimumDistance.toInt()) {
                appendPoint(x, y, time)
            }
            if (isMajor) {
                updateIncrementalRecognitionSize(x, y, time)
                updateMajorEvent(x, y, time)
            }
        }
        return true
    }

    /** Check if this is a gesture (not a tap). */
    fun isStartOfGesture(): Boolean {
        if (!hasDetectedFastMove()) return false
        val size = eventTimes.size
        if (size <= 0) return false
        val lastIndex = size - 1
        val deltaTime = eventTimes[lastIndex] - detectFastMoveTime
        if (deltaTime < 0) return false
        val deltaDistance = getDistance(
            xCoords[lastIndex], yCoords[lastIndex],
            detectFastMoveX, detectFastMoveY
        )
        val distanceThreshold = getDynamicDistanceThreshold(deltaTime)
        val timeThreshold = getDynamicTimeThreshold(deltaTime)
        return deltaTime >= timeThreshold && deltaDistance >= distanceThreshold
    }

    /**
     * Appends the finger's final position. Sampling keeps a point only when it is more than a sixth
     * of a key away from the previous one, so without this the last stretch of the swipe (up to
     * that distance) was dropped and the path could stop just short of the last letter.
     */
    fun finish(x: Float, y: Float) {
        val last = eventTimes.size - 1
        if (last < 0) return
        val xi = x.toInt()
        val yi = y.toInt()
        if (getDistance(xCoords[last], yCoords[last], xi, yi) < 2) return
        appendPoint(xi, yi, eventTimes[last] + 1)
    }

    /** Get the sampled path as a list of FloatArray(2) = [x, y]. */
    fun getPath(): List<FloatArray> {
        val result = ArrayList<FloatArray>(eventTimes.size)
        for (i in eventTimes.indices) {
            result.add(floatArrayOf(xCoords[i].toFloat(), yCoords[i].toFloat()))
        }
        return result
    }

    /** Get the incremental (partial) path for live preview. */
    fun getIncrementalPath(): List<FloatArray> {
        val size = minOf(incrementalRecognitionSize, eventTimes.size)
        val result = ArrayList<FloatArray>(size)
        for (i in 0 until size) {
            result.add(floatArrayOf(xCoords[i].toFloat(), yCoords[i].toFloat()))
        }
        return result
    }

    /**
     * Called on finger-up: marks the trail for whole-trail fade-out (the entire
     * the entire trail fades together over [trailFadeDuration], not per-point).
     */
    fun addTrail() {
        trailReleaseTime = clock()
    }

    /** True while there are trail points still visible (used to stop endless redraws). */
    fun hasTrail(): Boolean {
        if (trailX.size < 2) return false
        if (trailReleaseTime == 0L) {
            // Still drawing: trail lingers for [trailLingerDuration] after the last point
            // was added. After that it's gone (no more redraws).
            val last = if (trailTimes.isEmpty()) 0L else trailTimes.last()
            return clock() - last <= trailLingerDuration
        }
        val elapsed = clock() - trailReleaseTime
        return elapsed <= trailFadeDuration
    }

    /**
     * Draw the gesture trail as a smooth bezier curve with width taper and alpha fade.
     *
     * Trail characteristics:
     * - Smooth quadratic bezier through midpoints (no jagged segments)
     * - Width tapers from thick (near finger) to thin (at tail)
     * - Alpha fades from opaque (near finger) to transparent (at tail)
     * - Round stroke caps for a blob look
     * - After release, the whole trail fades out together over ~200ms
     */
    fun drawTrail(canvas: Canvas, paint: Paint) {
        val n = trailX.size
        if (n < 2) return

        // Compute global alpha for whole-trail fade-out after release.
        var globalAlpha = 1f
        if (trailReleaseTime > 0L) {
            val elapsed = clock() - trailReleaseTime
            if (elapsed >= trailFadeDuration) {
                trailX.clear(); trailY.clear(); trailTimes.clear()
                trailReleaseTime = 0L
                return
            }
            globalAlpha = 1f - elapsed.toFloat() / trailFadeDuration
        }

        // Build a smooth path through the trail points using quadratic beziers.
        // Method: connect consecutive midpoints with quadratic curves, using the
        // actual points as control points. This produces a smooth, C1-continuous
        // path that passes through the midpoints and approximates the original points.
        smoothPath.reset()
        smoothPath.moveTo(trailX[0], trailY[0])
        for (i in 1 until n - 1) {
            val midX = (trailX[i] + trailX[i + 1]) / 2f
            val midY = (trailY[i] + trailY[i + 1]) / 2f
            smoothPath.quadTo(trailX[i], trailY[i], midX, midY)
        }
        // Last segment: straight line to the final point
        smoothPath.lineTo(trailX[n - 1], trailY[n - 1])

        // Measure the path to draw it in segments with varying width and alpha.
        pathMeasure.setPath(smoothPath, false)
        val totalLen = pathMeasure.length
        if (totalLen <= 0f) return

        // Draw the trail in ~40 segments, each with its own width and alpha.
        // Segment 0 = tail (oldest, thin, transparent); last segment = head (newest, thick, opaque).
        val segments = max(2, min(40, n))
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Extract the base color's RGB (we'll modulate alpha per segment).
        val baseR = (trailColor shr 16) and 0xFF
        val baseG = (trailColor shr 8) and 0xFF
        val baseB = trailColor and 0xFF

        var prevX = 0f
        var prevY = 0f
        pathMeasure.getPosTan(0f, posBuffer, tanBuffer)
        prevX = posBuffer[0]
        prevY = posBuffer[1]

        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val distance = totalLen * t
            pathMeasure.getPosTan(distance, posBuffer, tanBuffer)
            val cx = posBuffer[0]
            val cy = posBuffer[1]

            // Width: thick at the head (t=1), thin at the tail (t=0).
            val width = trailEndWidth + (trailStartWidth - trailEndWidth) * t

            // Alpha: opaque at the head (t=1), transparent at the tail (t=0).
            // During live drawing, the tail is very short; after release the whole
            // trail fades together via globalAlpha.
            val segAlpha = (0.15f + 0.85f * t) * globalAlpha
            val alpha = (255 * segAlpha).toInt().coerceIn(0, 255)

            paint.color = (alpha shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
            paint.strokeWidth = width

            // Draw a small segment from prev to current.
            // Using drawLine with ROUND cap gives the smooth blob look.
            canvas.drawLine(prevX, prevY, cx, cy, paint)

            prevX = cx
            prevY = cy
        }
    }

    /** Check if enough time has passed for a new recognition pass. */
    fun hasRecognitionTimePast(currentTime: Long, lastRecognitionTime: Long): Boolean {
        return currentTime > lastRecognitionTime + recognitionMinimumTime
    }

    fun getLength(): Int = eventTimes.size

    // ---- Private methods ----

    private fun reset() {
        trailX.clear(); trailY.clear(); trailTimes.clear()
        trailReleaseTime = 0L
        incrementalRecognitionSize = 0
        eventTimes.clear()
        xCoords.clear()
        yCoords.clear()
        lastMajorEventTime = 0
        detectFastMoveTime = 0
        afterFastTyping = false
    }

    private fun appendPoint(x: Int, y: Int, time: Int) {
        val lastIndex = eventTimes.size - 1
        if (lastIndex >= 0 && eventTimes[lastIndex] > time) return // drop stale
        eventTimes.add(time)
        xCoords.add(x)
        yCoords.add(y)
        // Feed the visible trail live so it follows the finger (not only after release)
        trailX.add(x.toFloat())
        trailY.add(y.toFloat())
        trailTimes.add(clock())
        // Cap trail length to avoid unbounded memory on long swipes
        while (trailX.size > trailMaxPoints) {
            trailX.removeAt(0)
            trailY.removeAt(0)
            trailTimes.removeAt(0)
        }
    }

    private fun updateMajorEvent(x: Int, y: Int, time: Int) {
        lastMajorEventTime = time
        lastMajorEventX = x
        lastMajorEventY = y
    }

    private fun hasDetectedFastMove(): Boolean = detectFastMoveTime > 0

    private fun detectFastMove(x: Int, y: Int, time: Int): Int {
        val size = eventTimes.size
        val lastIndex = size - 1
        val lastX = xCoords[lastIndex]
        val lastY = yCoords[lastIndex]
        val dist = getDistance(lastX, lastY, x, y)
        val msecs = time - eventTimes[lastIndex]
        if (msecs > 0) {
            val pixelsPerSec = dist * 1000
            if (!hasDetectedFastMove() && pixelsPerSec > detectFastMoveSpeedThreshold * msecs) {
                detectFastMoveTime = time
                detectFastMoveX = x
                detectFastMoveY = y
            }
        }
        return dist
    }

    private fun updateIncrementalRecognitionSize(x: Int, y: Int, time: Int) {
        val msecs = time - lastMajorEventTime
        if (msecs <= 0) return
        val pixels = getDistance(lastMajorEventX, lastMajorEventY, x, y)
        val pixelsPerSec = pixels * 1000
        if (pixelsPerSec < recognitionSpeedThreshold * msecs) {
            incrementalRecognitionSize = eventTimes.size
        }
    }

    private fun getDynamicDistanceThreshold(deltaTime: Int): Int {
        if (!afterFastTyping || deltaTime >= dynamicThresholdDecayDuration) {
            return dynamicDistanceThresholdTo.toInt()
        }
        val decayedThreshold =
            (dynamicDistanceThresholdFrom - dynamicDistanceThresholdTo).toInt() *
            deltaTime / dynamicThresholdDecayDuration
        return dynamicDistanceThresholdFrom.toInt() - decayedThreshold
    }

    private fun getDynamicTimeThreshold(deltaTime: Int): Int {
        if (!afterFastTyping || deltaTime >= dynamicThresholdDecayDuration) {
            return dynamicTimeThresholdTo
        }
        val decayedThreshold =
            (dynamicTimeThresholdFrom - dynamicTimeThresholdTo) *
            deltaTime / dynamicThresholdDecayDuration
        return dynamicTimeThresholdFrom - decayedThreshold
    }

    private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
    }
}
