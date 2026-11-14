package com.noircipher.shade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Shade's monochrome-framed HSV color picker: a saturation/brightness square
 * on top and a hue rail below, both drawn and handled inside this single view
 * so no extra libraries are needed.
 *
 * The [onColorChanged] callback fires only for real touch interaction —
 * calling [setColor] programmatically never re-triggers it, which keeps the
 * hex field <-> picker sync loop-free.
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Fires only for touch interaction — [setColor] never triggers it. */
    var onColorChanged: ((hue: Float, sat: Float, value: Float) -> Unit)? = null

    private var hue = 0f
    private var sat = 1f
    private var valueOf = 1f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val corner = dp(14f)
    private val hueRailHeight = dp(36f)
    private val gap = dp(18f)
    private val svHeight = dp(180f)
    private val totalContentHeight = svHeight + gap + hueRailHeight

    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val cursorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.BLACK
    }
    private val hueCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val hueCursorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.BLACK
    }
    private val clipPath = Path()
    private val railPath = Path()
    private val svRect = RectF()
    private val railRect = RectF()

    init {
        // Pickers must always look right on a pitch-black sheet.
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(R.string.color_picker_desc)
    }

    /** Re-aims the picker without firing [onColorChanged]. */
    fun setColor(h: Float, s: Float, v: Float) {
        hue = (h % 360f + 360f) % 360f
        sat = s.coerceIn(0f, 1f)
        valueOf = v.coerceIn(0f, 1f)
        invalidate()
    }

    fun currentColor(): Int = ColorMath.hsvToRgb(hue, sat, valueOf)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = max(
            suggestedMinimumWidth,
            MeasureSpec.getSize(widthMeasureSpec)
        )
        setMeasuredDimension(width, (totalContentHeight + paddingTop + paddingBottom).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        svRect.set(0f, paddingTop.toFloat(), w.toFloat(), paddingTop + svHeight)
        railRect.set(
            0f, paddingTop + svHeight + gap,
            w.toFloat(), paddingTop + svHeight + gap + hueRailHeight
        )
        clipPath.reset()
        clipPath.addRoundRect(svRect, corner, corner, Path.Direction.CW)
        railPath.reset()
        railPath.addRoundRect(railRect, hueRailHeight / 2f, hueRailHeight / 2f, Path.Direction.CW)
        // The hue gradient depends on the real width; build it here once.
        huePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            HUE_STOPS, null, Shader.TileMode.CLAMP
        )
    }

    /** Six-segment hue spectrum, fully opaque. */
    private val HUE_STOPS = intArrayOf(
        Color.argb(255, 255, 0, 0),
        Color.argb(255, 255, 255, 0),
        Color.argb(255, 0, 255, 0),
        Color.argb(255, 0, 255, 255),
        Color.argb(255, 0, 0, 255),
        Color.argb(255, 255, 0, 255),
        Color.argb(255, 255, 0, 0)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        // Saturation/brightness square: solid hue → white horizontally → black vertically.
        val hueColor = ColorMath.hsvToRgb(hue, 1f, 1f)
        val base = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(svRect, svPaint.apply { color = hueColor })
        svPaint.shader = LinearGradient(
            svRect.left, 0f, svRect.right, 0f,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(svRect, svPaint)
        svPaint.shader = LinearGradient(
            0f, svRect.top, 0f, svRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        canvas.drawRect(svRect, svPaint)
        canvas.restoreToCount(base)

        // Cursor over the square (white ring + dark outline so it stays visible
        // on both white and black regions).
        val cx = svRect.left + sat * svRect.width()
        val cy = svRect.top + (1f - valueOf) * svRect.height()
        canvas.drawCircle(cx, cy, dp(9f), cursorOutline)
        canvas.drawCircle(cx, cy, dp(9f), cursorStroke)

        // Hue rail.
        val rail = canvas.save()
        canvas.clipPath(railPath)
        canvas.drawRect(railRect, huePaint)
        canvas.restoreToCount(rail)

        val hx = railRect.left + hue / 360f * railRect.width()
        val hy = railRect.centerY()
        canvas.drawCircle(hx, hy, dp(11f), hueCursorOutline)
        canvas.drawCircle(hx, hy, dp(11f), hueCursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                if (event.y <= svRect.bottom) {
                    sat = ((x / width.toFloat()).coerceIn(0f, 1f))
                    valueOf = 1f - ((event.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
                } else if (event.y >= railRect.top) {
                    hue = ((x / width.toFloat()).coerceIn(0f, 1f)) * 360f
                }
                invalidate()
                onColorChanged?.invoke(hue, sat, valueOf)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
