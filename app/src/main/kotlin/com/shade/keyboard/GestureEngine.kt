package com.shade.keyboard

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
    // Glide ribbon: a smooth, translucent ribbon follows the finger with a soft glow and a
    // small round blob at the head; while the finger is down the whole path stays visible, and on
    // release the remaining ribbon fades out over trailFadeDuration (Gboard-like timing).
    private val trailX = ArrayList<Float>()
    private val trailY = ArrayList<Float>()
    private val trailTimes = ArrayList<Long>()
    private var trailColor = 0x80FFFFFF.toInt()
    private var trailReleaseTime = 0L  // 0 = still drawing; >0 = finger lifted, fading out

    // Trail visual parameters. Widths are computed from [trailDensity] at draw time so the ribbon
    // keeps its physical size on every screen. Sized to Gboard's thin ribbon: a narrow core with
    // a modest glow, noticeably slimmer than the old 13dp/4.5dp ribbon.
    private var trailDensity = 2f
    private var trailFadeDuration = 220L     // ms for the whole-trail fade-out after release (Gboard's quick fade)
    private var trailMinPointDistance = 0f   // px between captured trail points (set from density)
    private val trailMaxPoints = 300         // cap memory; oldest points are dropped

    // Reusable buffers for smooth drawing
    private val smoothPath = Path()
    private val pathMeasure = PathMeasure()
    private val posBuffer = FloatArray(2)
    private val tanBuffer = FloatArray(2)

    fun setTrailColor(color: Int) { trailColor = color }

    /**
     * Screen density used to size the ribbon (set by KeyboardView). Call before the first stroke.
     */
    fun setTrailDensity(density: Float) {
        trailDensity = if (density > 0f) density else 2f
        trailMinPointDistance = 3f * trailDensity
    }

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

    /**
     * Feeds the visible ribbon a raw (unsampled) point. Decode sampling (eventTimes above) stays
     * sparse on purpose; the ribbon needs much denser points to look and feel smooth.
     */
    fun addTrailPoint(x: Float, y: Float) {
        val last = trailX.size - 1
        if (last >= 0) {
            val dx = x - trailX[last]
            val dy = y - trailY[last]
            if (dx * dx + dy * dy < trailMinPointDistance * trailMinPointDistance) return
        }
        trailX.add(x)
        trailY.add(y)
        trailTimes.add(clock())
        while (trailX.size > trailMaxPoints) {
            trailX.removeAt(0)
            trailY.removeAt(0)
            trailTimes.removeAt(0)
        }
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
     * trail fades together over [trailFadeDuration], not per-point).
     */
    fun addTrail() {
        trailReleaseTime = clock()
    }

    /**
     * True while the ribbon has points to draw. While the finger is down the path stays fully
     * visible (it must never vanish mid-glide); after release it fades for [trailFadeDuration].
     */
    fun hasTrail(): Boolean {
        if (trailX.size < 2) return false
        if (trailReleaseTime == 0L) return true
        return clock() - trailReleaseTime <= trailFadeDuration
    }

    /**
     * True only while the post-release fade is running — the caller keeps the redraw loop alive
     * for the fade; a static ribbon under a held finger needs no continuous redraws.
     */
    fun isTrailFading(): Boolean {
        if (trailReleaseTime == 0L) return false
        return clock() - trailReleaseTime <= trailFadeDuration
    }

    /**
     * Draw the gesture trail as a smooth glide ribbon.
     *
     * Trail characteristics:
     * - Smooth quadratic bezier through midpoints (no jagged corners) over densely captured points
     * - Soft glow pass under a solid core ribbon; round caps/joins everywhere
     * - Width tapers gently from a thick head (near the finger) to a thinner tail
     * - Alpha: fairly uniform (tail ~45%) so crossed keys stay readable, head ~90%
     * - A small round blob at the finger position (the glide head)
     * - While the finger is down the whole path stays visible; after release the whole ribbon
     *   fades out together over ~220ms (Gboard-like quick fade), easing in so it lingers briefly first
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
            // Ease-in fade: alpha = 1 - t². The ribbon holds near full opacity for the first
            // moment (t=0.5 still ~75%), then sweeps away — reads like Gboard's
            // linger-then-vanish instead of a linear dim.
            val frac = elapsed.toFloat() / trailFadeDuration
            globalAlpha = 1f - frac * frac
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

        val headW = 9f * trailDensity    // ribbon width at the finger (Gboard-thin)
        val tailW = 3f * trailDensity    // ribbon width at the oldest end
        val glowExtra = 4f * trailDensity

        // Segment count scales with path length so short strokes stay smooth and long glides
        // don't cost too much (the ribbon must read as a continuous curve, never a polyline).
        val segments = (totalLen / (6f * trailDensity)).toInt().coerceIn(24, 120)
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Extract the base color's RGB (we'll modulate alpha per segment).
        val baseR = (trailColor shr 16) and 0xFF
        val baseG = (trailColor shr 8) and 0xFF
        val baseB = trailColor and 0xFF

        var prevX: Float
        var prevY: Float
        pathMeasure.getPosTan(0f, posBuffer, tanBuffer)
        prevX = posBuffer[0]
        prevY = posBuffer[1]

        for (pass in 0..1) {
            // Pass 0 = soft glow under the ribbon; pass 1 = solid core.
            if (pass == 0) {
                paint.strokeWidth = headW + glowExtra
            }
            pathMeasure.getPosTan(0f, posBuffer, tanBuffer)
            prevX = posBuffer[0]
            prevY = posBuffer[1]
            for (i in 1..segments) {
                val t = i.toFloat() / segments
                val distance = totalLen * t
                pathMeasure.getPosTan(distance, posBuffer, tanBuffer)
                val cx = posBuffer[0]
                val cy = posBuffer[1]

                // Width: thick at the head (t=1), thinner at the tail (t=0).
                if (pass == 1) {
                    paint.strokeWidth = tailW + (headW - tailW) * t
                }

                // Alpha: head is nearly opaque, tail clearly translucent so keys stay readable.
                val segAlpha = ((0.45f + 0.45f * t) * if (pass == 0) 0.22f else 1f) * globalAlpha
                val alpha = (255 * segAlpha).toInt().coerceIn(0, 255)
                paint.color = (alpha shl 24) or (baseR shl 16) or (baseG shl 8) or baseB

                canvas.drawLine(prevX, prevY, cx, cy, paint)
                prevX = cx
                prevY = cy
            }
        }

        // Head blob: a small round dot exactly at the finger position (the glide head).
        val headAlpha = (255 * 0.92f * globalAlpha).toInt().coerceIn(0, 255)
        paint.color = (headAlpha shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
        paint.style = Paint.Style.FILL
        paint.strokeWidth = headW
        canvas.drawCircle(trailX[n - 1], trailY[n - 1], headW * 0.52f, paint)
        paint.style = Paint.Style.STROKE
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
