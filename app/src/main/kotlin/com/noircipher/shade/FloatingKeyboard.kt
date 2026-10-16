package com.noircipher.shade

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating keyboard overlay. Wraps a keyboard view in a WindowManager-managed
 * window that the user can drag by its bottom handle, resize from any corner,
 * and that auto-fades after inactivity.
 *
 * Mirror of Gboard's floating keyboard:
 *  - Drag from anywhere inside the window to move
 *  - Resize from the four corner brackets
 *  - Auto-fade to 50% after 10s of inactivity; tap to restore
 *  - Position is remembered and restored on next launch
 */
class FloatingKeyboard(private val host: Context) {

    interface Listener {
        fun onBoundsChanged(x: Int, y: Int, width: Int, height: Int)
        fun onFocusRestored()
    }

    var listener: Listener? = null

    private val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var params: WindowManager.LayoutParams? = null
    private var container: ContainerView? = null
    private var contentView: View? = null

    // Auto-fade
    private var fadeAnimator: ValueAnimator? = null
    private var faded = false
    private val fadeRunnable = Runnable { beginFade() }

    private var savedX = -1
    private var savedY = -1
    private var savedW = 0
    private var savedH = 0

    val isShowing: Boolean get() = container != null

    // ------------------------------------------------------------------ show / hide

    fun show(content: View, width: Int, height: Int, x: Int, y: Int) {
        if (isShowing) return
        contentView = content

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        params = WindowManager.LayoutParams(
            width, height, type, flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = if (savedX >= 0) savedX else x
            this.y = if (savedY >= 0) savedY else y
            if (savedW > 0) this.width = savedW
            if (savedH > 0) this.height = savedH
        }

        val c = ContainerView(host)
        c.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container = c

        try {
            wm.addView(c, params)
        } catch (_: Exception) {
            container = null
            params = null
            return
        }

        scheduleFade()
    }

    fun hide() {
        val c = container ?: return
        saveBounds()
        cancelFade()
        try { wm.removeView(c) } catch (_: Exception) {}
        container = null
        contentView = null
    }

    private fun saveBounds() {
        val p = params ?: return
        savedX = p.x
        savedY = p.y
        savedW = p.width
        savedH = p.height
        listener?.onBoundsChanged(p.x, p.y, p.width, p.height)
    }

    // ------------------------------------------------------------------ auto-fade

    private fun scheduleFade() {
        handler.removeCallbacks(fadeRunnable)
        handler.postDelayed(fadeRunnable, FADE_DELAY_MS)
    }

    private fun cancelFade() {
        handler.removeCallbacks(fadeRunnable)
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun beginFade() {
        if (faded) return
        val c = container ?: return
        faded = true
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(c.alpha, 0.5f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { c.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun restoreOpacity() {
        if (!faded) return
        val c = container ?: return
        faded = false
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(c.alpha, 1f).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { c.alpha = it.animatedValue as Float }
            start()
        }
        listener?.onFocusRestored()
    }

    /** Called by the host whenever the user interacts with the keyboard content. */
    fun noteUserActivity() {
        restoreOpacity()
        scheduleFade()
    }

    // ------------------------------------------------------------------ container view

    private inner class ContainerView(context: Context) : FrameLayout(context) {

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val handleRect = RectF()

        private var dragging = false
        private var resizing = false
        private var activeCorner = 0 // 1=TL, 2=TR, 3=BL, 4=BR
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var downW = 0
        private var downH = 0

        init {
            setWillNotDraw(false)
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Bottom drag handle
            handleRect.set(
                w / 2f - dp(28f),
                h - dp(10f),
                w / 2f + dp(28f),
                h - dp(4f)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Bottom drag handle
            handlePaint.color = 0x88FFFFFF.toInt()
            canvas.drawRoundRect(handleRect, dp(3f), dp(3f), handlePaint)

            // Corner brackets
            bracketPaint.color = 0x99FFFFFF.toInt()
            bracketPaint.strokeWidth = dp(2.5f)
            val b = dp(12f)
            val inset = dp(4f)

            // TL
            canvas.drawLine(inset, inset, inset + b, inset, bracketPaint)
            canvas.drawLine(inset, inset, inset, inset + b, bracketPaint)
            // TR
            canvas.drawLine(w - inset, inset, w - inset - b, inset, bracketPaint)
            canvas.drawLine(w - inset, inset, w - inset, inset + b, bracketPaint)
            // BL
            canvas.drawLine(inset, h - inset, inset + b, h - inset, bracketPaint)
            canvas.drawLine(inset, h - inset, inset, h - inset - b, bracketPaint)
            // BR
            canvas.drawLine(w - inset, h - inset, w - inset - b, h - inset, bracketPaint)
            canvas.drawLine(w - inset, h - inset, w - inset, h - inset - b, bracketPaint)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // Always allow the container to observe touches so it can
            // drag/resize/fade even when the content consumes them.
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    restoreOpacity()
                    scheduleFade()
                    val corner = hitCorner(ev.x, ev.y)
                    if (corner != 0) {
                        activeCorner = corner
                        resizing = true
                        dragging = false
                        captureDown(ev)
                        return true
                    }
                    if (hitHandle(ev.x, ev.y)) {
                        dragging = true
                        resizing = false
                        captureDown(ev)
                        return true
                    }
                    // Let the content view handle it
                    dragging = false
                    resizing = false
                    return false
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val corner = hitCorner(ev.x, ev.y)
                    if (corner != 0) {
                        activeCorner = corner
                        resizing = true
                        dragging = false
                        captureDown(ev)
                        return true
                    }
                    if (hitHandle(ev.x, ev.y)) {
                        dragging = true
                        resizing = false
                        captureDown(ev)
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val p = params ?: return false
                        p.x = downX + (ev.rawX - downRawX).toInt()
                        p.y = downY + (ev.rawY - downRawY).toInt()
                        clampToScreen(p)
                        try { wm.updateViewLayout(this@FloatingKeyboard.container, p) } catch (_: Exception) {}
                        return true
                    }
                    if (resizing) {
                        val p = params ?: return false
                        val dx = (ev.rawX - downRawX).toInt()
                        val dy = (ev.rawY - downRawY).toInt()
                        applyResize(p, dx, dy)
                        try { wm.updateViewLayout(this@FloatingKeyboard.container, p) } catch (_: Exception) {}
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging || resizing) {
                        dragging = false
                        resizing = false
                        activeCorner = 0
                        saveBounds()
                        scheduleFade()
                        return true
                    }
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun captureDown(ev: MotionEvent) {
            downRawX = ev.rawX
            downRawY = ev.rawY
            val p = params ?: return
            downX = p.x
            downY = p.y
            downW = p.width
            downH = p.height
        }

        private fun applyResize(p: WindowManager.LayoutParams, dx: Int, dy: Int) {
            val minW = dp(220f).toInt()
            val minH = dp(140f).toInt()
            when (activeCorner) {
                1 -> { // TL
                    val newW = (downW - dx).coerceAtLeast(minW)
                    val newH = (downH - dy).coerceAtLeast(minH)
                    p.x = downX + (downW - newW)
                    p.y = downY + (downH - newH)
                    p.width = newW
                    p.height = newH
                }
                2 -> { // TR
                    val newW = (downW + dx).coerceAtLeast(minW)
                    val newH = (downH - dy).coerceAtLeast(minH)
                    p.y = downY + (downH - newH)
                    p.width = newW
                    p.height = newH
                }
                3 -> { // BL
                    val newW = (downW - dx).coerceAtLeast(minW)
                    val newH = (downH + dy).coerceAtLeast(minH)
                    p.x = downX + (downW - newW)
                    p.width = newW
                    p.height = newH
                }
                4 -> { // BR
                    p.width = (downW + dx).coerceAtLeast(minW)
                    p.height = (downH + dy).coerceAtLeast(minH)
                }
            }
            clampToScreen(p)
        }

        private fun hitHandle(x: Float, y: Float): Boolean =
            x in (handleRect.left - dp(16f))..(handleRect.right + dp(16f)) &&
                y >= handleRect.top - dp(12f) &&
                y <= handleRect.bottom + dp(12f)

        private fun hitCorner(x: Float, y: Float): Int {
            val threshold = dp(28f)
            val w = width.toFloat()
            val h = height.toFloat()
            return when {
                x < threshold && y < threshold -> 1
                x > w - threshold && y < threshold -> 2
                x < threshold && y > h - threshold -> 3
                x > w - threshold && y > h - threshold -> 4
                else -> 0
            }
        }

        private fun clampToScreen(p: WindowManager.LayoutParams) {
            val metrics = host.resources.displayMetrics
            val maxX = metrics.widthPixels - p.width
            val maxY = metrics.heightPixels - p.height
            p.x = p.x.coerceIn(0, max(0, maxX))
            p.y = p.y.coerceIn(0, max(0, maxY))
        }
    }

    // ------------------------------------------------------------------ utilities

    private fun dp(v: Float): Float =
        v * host.resources.displayMetrics.density

    companion object {
        private const val FADE_DELAY_MS = 10_000L
    }
}
