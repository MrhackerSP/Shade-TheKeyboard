package com.noircipher.shade

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import kotlin.math.hypot
 }
 *   // On up:
 *   val path = gesture.getPath()
 *   // Draw trail:
 *   gesture.drawTrail(canvas, paint)
 */
class GestureEngine(private var keyWidth: Float) {
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
    private var trailStartWidth = 8f
    private var trailEndWidth = 2f
    private var trailFadeoutDelay = 300L // ms
    private var trailFadeoutDuration = 400L // ms
    private var trailLingerDuration = 800L // ms

    fun setTrailColor(color: Int) { trailColor = color }
    fun setTrailWidths(start: Float, end: Float) { trailStartWidth = start; trailEndWidth = end }

    /** Reset state for a new gesture stroke. */
    fun onDown(x: Float, y: Float, elapsedTime: Int) {
        reset()
        addEventPoint(x.toInt(), y.toInt(), elapsedTime, true)
    }

    /** Add a move event point. Returns true if the point is on the keyboard. */
    fun addEventPoint(x: Float, y: Float, elapsedTime: Int): Boolean {
        return addEventPoint(x.toInt(), y.toInt(), elapsedTime, false)
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
        }
        if (isMajor) {
            updateIncrementalRecognitionSize(x, y, time)
            updateMajorEvent(x, y, time)
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

    /** Add current points to the trail for rendering. */
    fun addTrail() {
        val now = SystemClock.uptimeMillis()
        for (i in eventTimes.indices) {
            trailX.add(xCoords[i].toFloat())
            trailY.add(yCoords[i].toFloat())
            trailTimes.add(now)
        }
    }

    /** Draw the fading gesture trail on the canvas. */
    fun drawTrail(canvas: Canvas, paint: Paint) {
        if (trailX.size < 2) return
        val now = SystemClock.uptimeMillis()
        val path = Path()
        paint.color = trailColor
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true

        // Remove old points (beyond linger duration)
        while (trailTimes.isNotEmpty() && now - trailTimes[0] > trailLingerDuration) {
            trailX.removeAt(0)
            trailY.removeAt(0)
            trailTimes.removeAt(0)
        }

        if (trailX.size < 2) return

        // Draw trail with tapering width and alpha fade
        var startX = trailX[0]
        var startY = trailY[0]
        for (i in 1 until trailX.size) {
            val elapsedTime = now - trailTimes[i]
            val alpha = getTrailAlpha(elapsedTime)
            val width = getTrailWidth(elapsedTime)

            paint.alpha = alpha
            paint.strokeWidth = width
            path.reset()
            path.moveTo(startX, startY)
            path.lineTo(trailX[i], trailY[i])
            canvas.drawPath(path, paint)

            startX = trailX[i]
            startY = trailY[i]
        }
    }

    /** Check if enough time has passed for a new recognition pass. */
    fun hasRecognitionTimePast(currentTime: Long, lastRecognitionTime: Long): Boolean {
        return currentTime > lastRecognitionTime + recognitionMinimumTime
    }

    fun getLength(): Int = eventTimes.size

    // ---- Private methods ----

    private fun reset() {
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

    private fun getTrailAlpha(elapsedTime: Long): Int {
        if (elapsedTime < trailFadeoutDelay) return 255
        val decreasingAlpha = (255 * (elapsedTime - trailFadeoutDelay) / trailFadeoutDuration).toInt()
        return (255 - decreasingAlpha).coerceIn(0, 255)
    }

    private fun getTrailWidth(elapsedTime: Long): Float {
        val deltaWidth = trailStartWidth - trailEndWidth
        return (trailStartWidth - (deltaWidth * elapsedTime) / trailLingerDuration)
            .coerceIn(trailEndWidth, trailStartWidth)
    }

    private fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toInt()
    }
}
