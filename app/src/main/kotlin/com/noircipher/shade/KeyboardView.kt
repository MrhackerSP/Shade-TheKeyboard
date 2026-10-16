package com.noircipher.shade

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.SparseArray
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(code: Int)
        fun onText(text: String)
        fun onCursor(delta: Int)
        fun onCursorMove(dx: Int, dy: Int)
        fun onLongPressSpecial()
        fun onEnterLongPress()
        fun onTrackpadEnter()
        fun onTrackpadExit()
        fun onDeleteWords(count: Int)
        fun onDeletePreview(travelPx: Float): Int
        fun onDeleteCommit()
        fun onDeleteCancel()
    }

    var listener: Listener? = null
    var kbTheme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }
    var rows: List<List<Key>> = emptyList()
        private set
    var contentHeightPx = 0
    var oneHanded = 0
        private set
    var haptic = true
    var sound = false
    var showPreview = true
    var shiftState = 0
        set(v) { field = v; invalidate() }
    var enterLabel = ""
        set(v) { field = v; invalidate() }
    var enterIsPrimary = false
        set(v) { field = v; invalidate() }
    var keyBorders = true
    var splitKeyboard = false
    var showHints = true

    var longPressMs = 300L
    var repeatDelayMs = 350L
    var repeatIntervalMs = 40L

    var typingBurstEnabled = false

    var bgBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    var trackpadEnabled = true
    private var trackpadActive = false
    private var trackpadStartX = 0f
    private var trackpadStartY = 0f
    private var trackpadLastSentX = 0f
    private var trackpadLastSentY = 0f
    private val trackpadStep = 22f

    private var deleteSlideActive = false
    private var deleteSlideStartX = 0f
    private var deleteSlideWordCount = 0

    private var splitProgress = if (splitKeyboard) 1f else 0f
    private var splitAnimator: ValueAnimator? = null

    private var oneHandedProgress = 1f
    private var oneHandedAnimator: ValueAnimator? = null

    private var layoutAlpha = 1f
    private var layoutAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18000000
    }

    private val rect = RectF()
    private val dst = RectF()
    private val path = Path()
    private val clipBounds = Rect()
    private val pressedKeys = HashSet<Key>(16)
    private val cancelIds = IntArray(20)
    private var cancelCount = 0
    private val pointerPool = ArrayDeque<Pointer>(8)
    private var rowHeight = 0f

    private var pressProgress = 1f
    private var pressAnimator: ValueAnimator? = null

    private var shiftTapProgress = 0f
    private var shiftTapAnimator: ValueAnimator? = null

    private var previewKey: Key? = null
    private var previewProgress = 0f
    private var previewAnimator: ValueAnimator? = null
    private val previewBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewHue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val previewShadow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var altShown = false
    private var altItems: List<String> = emptyList()
    private var altSel = 0
    private var altCell = 0f
    private var altOriginX = 0f
    private var altTop = 0f
    private var altProgress = 0f
    private var altAnimator: ValueAnimator? = null
    private val altBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val altSelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val altText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val altRect = RectF()
    private val altShadow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var trailActive = false
    private var trailStartX = 0f
    private var trailStartY = 0f
    private var trailEndX = 0f
    private var trailEndY = 0f
    private var trailAlpha = 0f
    private var trailAnimator: ValueAnimator? = null
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var lastKeyTime = 0L
    private var burstCount = 0
    private var burstActive = false
    private var burstProgress = 0f
    private var burstAnimator: ValueAnimator? = null
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            if (vm is android.os.VibratorManager) vm.defaultVibrator else null
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private class Pointer {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var longRunnable: Runnable? = null
        var repeatRunnable: Runnable? = null
        var consumed = false
        var swiping = false
        var repeated = false

        fun reset(k: Key?, x: Float, y: Float) {
            key = k; downX = x; downY = y; lastX = x
            longRunnable = null; repeatRunnable = null
            consumed = false; swiping = false; repeated = false
        }
    }

    private val pointers = SparseArray<Pointer>()

    private fun obtainPointer(k: Key?, x: Float, y: Float): Pointer {
        val p = pointerPool.pollFirst() ?: Pointer()
        p.reset(k, x, y)
        return p
    }

    private fun recyclePointer(p: Pointer) {
        p.key = null
        p.longRunnable = null
        p.repeatRunnable = null
        if (pointerPool.size < 8) pointerPool.offerLast(p)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ------------------------------------------------------------------ layout

    fun setRows(r: List<List<Key>>) {
        rows = r
        layoutKeys()
        invalidate()
    }

    fun setRowsAnimated(r: List<List<Key>>) {
        layoutAnimator?.cancel()
        layoutAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 80L
            interpolator = DecelerateInterpolator()
            addUpdateListener { layoutAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rows = r
                    layoutKeys()
                    layoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 120L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { layoutAlpha = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
            })
            start()
        }
    }

    fun relayout() { layoutKeys(); invalidate() }

    fun setSplitAnimated(split: Boolean) {
        splitKeyboard = split
        val target = if (split) 1f else 0f
        if (splitProgress == target) return
        splitAnimator?.cancel()
        splitAnimator = ValueAnimator.ofFloat(splitProgress, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                splitProgress = it.animatedValue as Float
                layoutKeys()
                invalidate()
            }
            start()
        }
    }

    /**
     * Gboard-style one-handed transition. Shrinks the keyboard to 75% width
     * and slides it to the chosen edge over 200ms.
     */
    fun setOneHandedAnimated(side: Int) {
        oneHanded = side
        val target = if (side == 0) 1f else 0.75f
        if (oneHandedProgress == target) return
        oneHandedAnimator?.cancel()
        oneHandedAnimator = ValueAnimator.ofFloat(oneHandedProgress, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                oneHandedProgress = it.animatedValue as Float
                layoutKeys()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (contentHeightPx > 0) contentHeightPx else dp(200f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || rows.isEmpty()) return

        // One-handed: shrink to 75% and slide to the chosen edge.
        val availW = w * oneHandedProgress
        val offX = when (oneHanded) {
            1 -> 0f
            2 -> w - availW
            else -> 0f
        }

        val padTop = dp(5f)
        val padBottom = dp(5f)
        val bottomRowPad = dp(6f)
        val defaultPad = dp(3f)
        rowHeight = (h - padTop - padBottom) / rows.size
        val maxGap = min(dp(72f), w * 0.15f)
        val gap = maxGap * splitProgress

        rows.forEachIndexed { r, row ->
            val isBottom = r == rows.lastIndex
            val padX = if (isBottom) bottomRowPad else defaultPad
            val eligible = gap > 0.5f && row.size >= 6 && row.none { it.kind == KeyKind.SPACE }
            val rowGap = if (eligible) gap else 0f
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val bottomExtraGaps = if (isBottom) dp(3f) * (row.size - 1) else 0f
            val unit = (availW - 2 * padX - rowGap - bottomExtraGaps) / total

            var x = offX + padX
            var cum = 0f
            var gapDone = false
            for ((idx, k) in row.withIndex()) {
                if (eligible && !gapDone && cum >= total / 2f - 0.01f) {
                    x += rowGap
                    gapDone = true
                }
                k.left = x
                k.right = x + k.weight * unit
                k.top = padTop + r * rowHeight
                k.bottom = k.top + rowHeight
                k.updateInvalidateRect(dp(3f).toInt())
                x = k.right
                cum += k.weight
                if (isBottom && idx < row.size - 1) x += dp(3f)
            }
        }

        val lastRow = rows.lastOrNull() ?: return
        val visible = lastRow.filter { it.kind != KeyKind.SPACER }
        for (k in visible) k.outerCorner = KeyOuterCorner.NONE
        if (visible.isNotEmpty()) {
            visible.first().outerCorner = KeyOuterCorner.BOTTOM_LEFT
            visible.last().outerCorner = KeyOuterCorner.BOTTOM_RIGHT
        }
    }

    private fun hit(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val ri = rows.indices.minByOrNull { i ->
            val k = rows[i][0]
            when {
                y < k.top -> k.top - y
                y > k.bottom -> y - k.bottom
                else -> 0f
            }
        } ?: return null
        return rows[ri].filter { it.kind != KeyKind.SPACER }.minByOrNull { k ->
            when {
                x < k.left -> k.left - x
                x > k.right -> x - k.right
                else -> 0f
            }
        }
    }

    private fun display(s: String): String {
        if (shiftState > 0 && s.length == 1 && s[0].isLetter()) {
            val u = s.uppercase()
            if (u.length == 1) return u
        }
        return s
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(kbTheme.bg)
        val bmp = bgBitmap
        if (bmp != null) {
            val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            c.drawBitmap(bmp, null, dst, null)
            fill.color = ColorUtils.setAlphaComponent(kbTheme.bg, 0x55)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        pressedKeys.clear()
        val n = pointers.size()
        for (i in 0 until n) pointers.valueAt(i).key?.let { pressedKeys.add(it) }

        c.getClipBounds(clipBounds)
        val clipL = clipBounds.left.toFloat()
        val clipT = clipBounds.top.toFloat()
        val clipR = clipBounds.right.toFloat()
        val clipB = clipBounds.bottom.toFloat()

        val gx = dp(2.5f)
        val gy = dp(3f)
        val radius = dp(7f)
        val combinedAlpha = ((if (trackpadActive) 0x88 else 0xFF) * layoutAlpha).toInt().coerceIn(0, 255)
        val pressDown = dp(0.7f) * pressProgress

        for (row in rows) for (k in row) {
            if (k.kind == KeyKind.SPACER) continue
            if (k.right < clipL || k.left > clipR || k.bottom < clipT || k.top > clipB) continue
            val isPressed = k in pressedKeys
            val topShift = if (isPressed) pressDown else 0f
            rect.set(
                k.left + gx,
                k.top + gy + topShift,
                k.right - gx,
                k.bottom - gy + topShift
            )

            val special = k.kind == KeyKind.ENTER || (k.code == Codes.SHIFT && shiftState == 2)
            if (!keyBorders && !isPressed && !special) {
                drawContent(c, k, combinedAlpha)
                continue
            }

            if (keyBorders) {
                shadowPaint.color = 0x18000000
                rect.set(
                    k.left + gx,
                    k.top + gy + dp(1.5f),
                    k.right - gx,
                    k.bottom - gy + dp(1.5f)
                )
                c.drawRoundRect(rect, radius, radius, shadowPaint)
                rect.set(
                    k.left + gx,
                    k.top + gy + topShift,
                    k.right - gx,
                    k.bottom - gy + topShift
                )
            }

            var baseCol = when {
                k.kind == KeyKind.ENTER && enterIsPrimary -> kbTheme.accent
                k.kind == KeyKind.ENTER -> kbTheme.keyFn
                k.kind == KeyKind.FUNC && k.code == Codes.SHIFT && shiftState == 2 -> kbTheme.keyFn
                k.kind == KeyKind.FUNC -> kbTheme.keyFn
                else -> kbTheme.key
            }
            if (bmp != null && !isPressed) baseCol = ColorUtils.setAlphaComponent(baseCol, 0xCC)
            val col = if (isPressed) {
                ColorUtils.blendARGB(baseCol, kbTheme.pressed, pressProgress)
            } else {
                baseCol
            }
            fill.color = ColorUtils.setAlphaComponent(col, combinedAlpha)

            val outer = k.outerCorner
            if (outer == KeyOuterCorner.NONE) {
                c.drawRoundRect(rect, radius, radius, fill)
            } else {
                drawOuterRoundedKey(c, k, radius, outer)
            }
            drawContent(c, k, combinedAlpha)

            if (k.code == Codes.DELETE && deleteSlideActive && deleteSlideWordCount > 0) {
                drawDeleteSlideBadge(c, k)
            }
        }

        if (trailActive && trailAlpha > 0f) drawTrail(c)
        if (burstActive && typingBurstEnabled) drawTypingBurst(c)
        if (altShown && altItems.isNotEmpty()) drawAltStrip(c)
        previewKey?.let { if (previewProgress > 0f) drawKeyPreview(c, it) }
    }

    private fun drawDeleteSlideBadge(c: Canvas, k: Key) {
        val cx = k.centerX
        val cy = k.centerY - dp(6f)
        val badge = deleteSlideWordCount.toString()
        val r = dp(9f)
        val badgeY = cy - r - dp(12f)
        fill.color = kbTheme.accent
        c.drawCircle(cx, badgeY, r, fill)
        textPaint.color = kbTheme.accentText
        textPaint.textSize = dp(11f)
        c.drawText(badge, cx, badgeY - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawOuterRoundedKey(
        c: Canvas,
        k: Key,
        radius: Float,
        which: KeyOuterCorner
    ) {
        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom

        c.drawRoundRect(rect, radius, radius, fill)

        path.reset()
        if (which == KeyOuterCorner.BOTTOM_LEFT) {
            path.addRoundRect(
                RectF(left, top, left + radius * 2f, bottom),
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f),
                Path.Direction.CW
            )
            path.addRect(left, top, left + radius, bottom - radius, Path.Direction.CW)
        } else {
            path.addRoundRect(
                RectF(right - radius * 2f, top, right, bottom),
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius),
                Path.Direction.CW
            )
            path.addRect(right - radius, top, right, bottom - radius, Path.Direction.CW)
        }
        c.drawPath(path, fill)
    }

    private fun textY(p: Paint, cy: Float) = cy - (p.ascent() + p.descent()) / 2f

    private fun drawContent(c: Canvas, k: Key, alpha: Int) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val s = min(rect.height(), rect.width()) * 0.22f
        when (k.kind) {
            KeyKind.CHAR -> {
                textPaint.color = ColorUtils.setAlphaComponent(kbTheme.text, alpha)
                textPaint.textSize = min(rowHeight * 0.40f, dp(24f))
                c.drawText(display(k.label), cx, textY(textPaint, cy), textPaint)
                if (showHints && k.alts.isNotEmpty() && k.alts[0].isDigit()) {
                    textPaint.color = ColorUtils.setAlphaComponent(kbTheme.hint, alpha)
                    textPaint.textSize = dp(10f)
                    c.drawText(k.alts.substring(0, 1), rect.right - dp(7f), rect.top + dp(12f), textPaint)
                }
            }
            KeyKind.SPACE -> Unit
            KeyKind.ENTER -> {
                if (enterLabel.isNotEmpty()) {
                    textPaint.color = ColorUtils.setAlphaComponent(
                        if (enterIsPrimary) kbTheme.accentText else kbTheme.text, alpha
                    )
                    textPaint.textSize = dp(14f)
                    c.drawText(enterLabel, cx, textY(textPaint, cy), textPaint)
                } else {
                    stroke.color = ColorUtils.setAlphaComponent(
                        if (enterIsPrimary) kbTheme.accentText else kbTheme.text, alpha
                    )
                    stroke.strokeWidth = dp(2f)
                    stroke.style = Paint.Style.STROKE
                    path.reset()
                    path.moveTo(cx + s, cy - s * 0.9f)
                    path.lineTo(cx + s, cy)
                    path.lineTo(cx - s, cy)
                    path.moveTo(cx - s + s * 0.7f, cy - s * 0.7f)
                    path.lineTo(cx - s, cy)
                    path.lineTo(cx - s + s * 0.7f, cy + s * 0.7f)
                    c.drawPath(path, stroke)
                }
            }
            KeyKind.FUNC -> when (k.code) {
                Codes.SHIFT -> drawShift(c, cx, cy, s, alpha)
                Codes.DELETE -> drawDelete(c, cx, cy, s, alpha)
                Codes.EMOJI -> drawSmiley(c, cx, cy, s, alpha)
                else -> {
                    textPaint.color = ColorUtils.setAlphaComponent(kbTheme.text, alpha)
                    textPaint.textSize = dp(14f)
                    c.drawText(k.label, cx, textY(textPaint, cy), textPaint)
                }
            }
            KeyKind.SPACER -> Unit
        }
    }

    private fun drawShift(c: Canvas, cx: Float, cy0: Float, s0: Float, alpha: Int) {
        val caps = shiftState == 2
        val shiftBoost = 1f + 0.18f * shiftTapProgress
        val s = s0 * shiftBoost
        val cy = if (caps) cy0 - s * 0.1f else cy0
        val col = kbTheme.text

        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx + s * 0.45f, cy)
        path.lineTo(cx + s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx - s * 0.45f, cy)
        path.lineTo(cx - s, cy)
        path.close()
        stroke.color = ColorUtils.setAlphaComponent(col, alpha)
        stroke.strokeWidth = dp(2f)
        stroke.style = if (shiftState > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(path, stroke)

        if (caps) {
            stroke.style = Paint.Style.FILL
            stroke.color = ColorUtils.setAlphaComponent(kbTheme.accent, alpha)
            c.drawCircle(cx, cy + s * 1.35f, dp(2.5f), stroke)
            stroke.style = Paint.Style.STROKE
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float, alpha: Int) {
        stroke.color = ColorUtils.setAlphaComponent(kbTheme.text, alpha)
        stroke.strokeWidth = dp(2f)
        stroke.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(cx - s * 1.1f, cy)
        path.lineTo(cx - s * 0.4f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.4f, cy + s * 0.8f)
        path.close()
        c.drawPath(path, stroke)
        c.drawLine(cx - s * 0.05f, cy - s * 0.35f, cx + s * 0.65f, cy + s * 0.35f, stroke)
        c.drawLine(cx - s * 0.05f, cy + s * 0.35f, cx + s * 0.65f, cy - s * 0.35f, stroke)
    }

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, s: Float, alpha: Int) {
        stroke.color = ColorUtils.setAlphaComponent(kbTheme.text, alpha)
        stroke.strokeWidth = dp(1.8f)
        stroke.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s * 0.95f, stroke)
        fill.color = ColorUtils.setAlphaComponent(kbTheme.text, alpha)
        c.drawCircle(cx - s * 0.35f, cy - s * 0.25f, s * 0.11f, fill)
        c.drawCircle(cx + s * 0.35f, cy - s * 0.25f, s * 0.11f, fill)
        rect.set(cx - s * 0.5f, cy - s * 0.45f, cx + s * 0.5f, cy + s * 0.45f)
        c.drawArc(rect, 20f, 140f, false, stroke)
    }

    // ------------------------------------------------------------------ spacebar trail

    private fun drawTrail(c: Canvas) {
        val a = trailAlpha
        if (a <= 0f) return
        val x0 = trailStartX
        val y0 = trailStartY
        val x1 = trailEndX
        val y1 = trailEndY

        trailGlowPaint.color = ColorUtils.setAlphaComponent(kbTheme.accent, (a * 0x22).toInt())
        trailGlowPaint.strokeWidth = dp(10f)
        c.drawLine(x0, y0, x1, y1, trailGlowPaint)

        trailPaint.color = ColorUtils.setAlphaComponent(kbTheme.accent, (a * 0xAA).toInt())
        trailPaint.strokeWidth = dp(3f)
        c.drawLine(x0, y0, x1, y1, trailPaint)

        fill.color = ColorUtils.setAlphaComponent(kbTheme.accent, (a * 0xCC).toInt())
        c.drawCircle(x1, y1, dp(5f), fill)
    }

    private fun startTrail(x: Float, y: Float) {
        trailActive = true
        trailStartX = x
        trailStartY = y
        trailEndX = x
        trailEndY = y
        trailAlpha = 0f
        trailAnimator?.cancel()
        trailAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90L
            addUpdateListener { trailAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateTrail(x: Float, y: Float) {
        if (!trailActive) return
        trailEndX = x
        trailEndY = y
        invalidate()
    }

    private fun endTrail() {
        if (!trailActive) return
        trailAnimator?.cancel()
        trailAnimator = ValueAnimator.ofFloat(trailAlpha, 0f).apply {
            duration = 180L
            addUpdateListener { trailAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    trailActive = false
                    invalidate()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ preview / alt

    private fun drawKeyPreview(c: Canvas, k: Key) {
        val label = display(k.label)
        val baseDiameter = maxOf(k.right - k.left, k.bottom - k.top) * 1.45f
        val diameter = baseDiameter * (0.65f + 0.35f * previewProgress)
        val radius = diameter / 2f
        val cx = k.centerX
        var cy = k.top - dp(8f) - radius
        if (cy - radius < dp(2f)) {
            cy = k.bottom + dp(8f) + radius
        }
        val cl = cx.coerceIn(radius + dp(2f), width - radius - dp(2f))
        val ct = cy.coerceIn(radius + dp(2f), height - radius - dp(2f))

        previewShadow.color = 0x55000000
        c.drawCircle(cl, ct + dp(2f), radius, previewShadow)
        previewBg.color = kbTheme.popupBg
        c.drawCircle(cl, ct, radius, previewBg)
        previewHue.color = ColorUtils.setAlphaComponent(kbTheme.accent, 0x33)
        c.drawCircle(cl, ct, radius * 0.82f, previewHue)
        previewText.color = kbTheme.text
        previewText.textSize = min(radius * 1.1f, dp(34f))
        c.drawText(label, cl, ct - (previewText.ascent() + previewText.descent()) / 2f, previewText)
    }

    private fun showPreviewFor(k: Key) {
        if (!showPreview) return
        previewKey = k
        previewAnimator?.cancel()
        previewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 95L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { previewProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun hidePreview() {
        if (previewKey == null) return
        previewAnimator?.cancel()
        previewAnimator = ValueAnimator.ofFloat(previewProgress, 0f).apply {
            duration = 70L
            interpolator = DecelerateInterpolator()
            addUpdateListener { previewProgress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    previewKey = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun drawAltStrip(c: Canvas) {
        val n = altItems.size
        if (n == 0) return
        val scale = 0.75f + 0.25f * altProgress
        val cell = altCell
        val stripH = dp(52f) * scale
        val stripW = cell * n
        val left = altOriginX
        val top = altTop + (dp(52f) - stripH)

        altRect.set(left, top + dp(3f), left + stripW, top + dp(3f) + stripH)
        altShadow.color = 0x55000000
        val r = dp(16f) * scale
        c.drawRoundRect(altRect, r, r, altShadow)

        altRect.set(left, top, left + stripW, top + stripH)
        altBg.color = kbTheme.popupBg
        c.drawRoundRect(altRect, r, r, altBg)

        val selLeft = left + altSel * cell + dp(4f)
        val selRight = left + (altSel + 1) * cell - dp(4f)
        altRect.set(selLeft, top + dp(4f), selRight, top + stripH - dp(4f))
        altSelPaint.color = kbTheme.accent
        c.drawRoundRect(altRect, r * 0.75f, r * 0.75f, altSelPaint)

        altText.textSize = min(stripH * 0.55f, dp(22f))
        for (i in 0 until n) {
            altText.color = if (i == altSel) kbTheme.accentText else kbTheme.text
            val cx = left + i * cell + cell / 2f
            val cy = top + stripH / 2f - (altText.ascent() + altText.descent()) / 2f
            c.drawText(altItems[i], cx, cy, altText)
        }
    }

    private fun showAlt(p: Pointer, k: Key) {
        val chars = k.alts.map { display(it.toString()) }.distinct()
        if (chars.isEmpty()) return
        val n = chars.size
        val cell = min(dp(46f), (width - dp(8f)) / n)
        val pw = cell * n
        val leftMode = k.centerX < width / 2f
        val origin = (if (leftMode) k.left else k.right - pw)
            .coerceIn(0f, maxOf(0f, (width - pw).toFloat()))

        altItems = if (leftMode) chars else chars.reversed()
        altOriginX = origin
        altCell = cell
        altTop = (k.top - dp(58f)).coerceAtLeast(dp(2f))
        altShown = true
        altSel = ((p.lastX - origin) / cell).toInt().coerceIn(0, n - 1)

        altAnimator?.cancel()
        altAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { altProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateAlt(p: Pointer, x: Float) {
        if (!altShown || altItems.isEmpty()) return
        val idx = ((x - altOriginX) / altCell).toInt().coerceIn(0, altItems.size - 1)
        if (idx != altSel) {
            altSel = idx
            hapticTick()
            invalidate()
        }
    }

    private fun dismissAlt() {
        if (!altShown) return
        altAnimator?.cancel()
        altAnimator = ValueAnimator.ofFloat(altProgress, 0f).apply {
            duration = 80L
            interpolator = DecelerateInterpolator()
            addUpdateListener { altProgress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    altShown = false
                    altItems = emptyList()
                    invalidate()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ typing burst

    private fun drawTypingBurst(c: Canvas) {
        val intensity = burstProgress
        if (intensity <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val maxR = maxOf(width, height) * 0.6f
        val r = maxR * (1f - intensity)
        burstPaint.color = ColorUtils.setAlphaComponent(kbTheme.accent, (intensity * 0x60).toInt())
        burstPaint.strokeWidth = dp(2f) + dp(3f) * intensity
        c.drawCircle(cx, cy, r, burstPaint)
    }

    private fun onKeyBurst() {
        if (!typingBurstEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyTime < 120L) burstCount++ else burstCount = 1
        lastKeyTime = now
        if (burstCount >= 6 && !burstActive) {
            burstActive = true
            burstAnimator?.cancel()
            burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450L
                interpolator = DecelerateInterpolator()
                addUpdateListener { burstProgress = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        burstActive = false; burstProgress = 0f; burstCount = 0; invalidate()
                    }
                })
                start()
            }
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (trackpadActive) return handleTrackpad(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val n = e.pointerCount
                for (i in 0 until n) move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                up(e.getPointerId(e.actionIndex), false)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCount = 0
                val n = pointers.size()
                if (cancelIds.size < n) { cancelAllSlow(); return true }
                for (i in 0 until n) cancelIds[cancelCount++] = pointers.keyAt(i)
                for (i in 0 until cancelCount) up(cancelIds[i], true)
            }
        }
        return true
    }

    private fun handleTrackpad(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackpadStartX = e.x; trackpadStartY = e.y
                trackpadLastSentX = 0f; trackpadLastSentY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - trackpadStartX
                val dy = e.y - trackpadStartY
                val stepX = ((dx - trackpadLastSentX) / trackpadStep).toInt()
                val stepY = ((dy - trackpadLastSentY) / trackpadStep).toInt()
                if (stepX != 0 || stepY != 0) {
                    listener?.onCursorMove(-stepX, -stepY)
                    trackpadLastSentX += stepX * trackpadStep
                    trackpadLastSentY += stepY * trackpadStep
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> exitTrackpad()
        }
        return true
    }

    private fun enterTrackpad() {
        if (trackpadActive) return
        trackpadActive = true
        hapticHeavy()
        listener?.onTrackpadEnter()
        invalidate()
    }

    private fun exitTrackpad() {
        if (!trackpadActive) return
        trackpadActive = false
        listener?.onTrackpadExit()
        invalidate()
    }

    private fun cancelAllSlow() {
        val n = pointers.size()
        val ids = IntArray(n)
        for (i in 0 until n) ids[i] = pointers.keyAt(i)
        for (id in ids) up(id, true)
    }

    private fun down(id: Int, x: Float, y: Float) {
        val k = hit(x, y) ?: return
        val p = obtainPointer(k, x, y)
        pointers.put(id, p)
        feedback(k)
        onKeyBurst()
        startPressAnimation()
        pressKey(p)
        invalidate(k)
    }

    private fun startPressAnimation() {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 55L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { pressProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPressAnimation() {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressProgress, 0f).apply {
            duration = 110L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { pressProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateShiftTap() {
        shiftTapAnimator?.cancel()
        shiftTapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shiftTapProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shiftTapAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = 140L
                        addUpdateListener {
                            shiftTapProgress = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun pressKey(p: Pointer) {
        val k = p.key ?: return
        if (k.kind == KeyKind.CHAR) showPreviewFor(k)
        if (k.kind == KeyKind.FUNC && k.code == Codes.SHIFT) {
            animateShiftTap()
        }
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            val r = Runnable { startDeleteRepeat(p) }
            p.longRunnable = r
            handler.postDelayed(r, longPressMs)
            return
        }
        // Enter long-press triggers Gboard-style one-handed mode menu.
        val longable = k.kind == KeyKind.SPACE ||
            k.kind == KeyKind.ENTER ||
            (k.kind == KeyKind.CHAR && k.alts.isNotEmpty()) ||
            (k.kind == KeyKind.FUNC && k.code == Codes.EMOJI)
        if (longable) {
            val r = Runnable { longPress(p) }
            p.longRunnable = r
            handler.postDelayed(r, longPressMs)
        }
    }

    private fun startDeleteRepeat(p: Pointer) {
        val k = p.key ?: return
        if (k.kind != KeyKind.FUNC || k.code != Codes.DELETE) return
        if (p.consumed) return

        p.longRunnable = null
        p.repeated = true
        hapticHeavy()
        listener?.onKey(Codes.DELETE)

        val repeat = object : Runnable {
            override fun run() {
                if (p.consumed) return
                if (p.key !== k) return
                listener?.onKey(Codes.DELETE)
                handler.postDelayed(this, repeatIntervalMs)
            }
        }
        p.repeatRunnable = repeat
        handler.postDelayed(repeat, repeatDelayMs)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        val k = p.key ?: return
        if (altShown) {
            p.lastX = x
            updateAlt(p, x)
            return
        }
        if (k.kind == KeyKind.SPACE) {
            if (p.consumed) return
            if (!p.swiping && abs(x - p.downX) > dp(14f)) {
                p.swiping = true
                cancelLong(p)
                hidePreview()
                p.lastX = x
                startTrail(p.downX, p.downY)
            }
            if (p.swiping) {
                val step = dp(14f)
                while (x - p.lastX >= step) { listener?.onCursor(1); p.lastX += step }
                while (p.lastX - x >= step) { listener?.onCursor(-1); p.lastX -= step }
                updateTrail(x, y)
            }
            return
        }
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            val dx = x - p.downX
            if (!deleteSlideActive && dx < -dp(20f)) {
                deleteSlideActive = true
                deleteSlideStartX = p.downX
                deleteSlideWordCount = 0
                cancelLong(p)
                hapticTick()
            }
            if (deleteSlideActive) {
                val travel = (p.downX - x).coerceAtLeast(0f)
                val words = listener?.onDeletePreview(travel) ?: 0
                if (words != deleteSlideWordCount) {
                    deleteSlideWordCount = words
                    hapticTick()
                    invalidate()
                }
            }
            return
        }
        val nk = hit(x, y)
        if (nk != null && nk !== k && !(nk.kind == KeyKind.FUNC && nk.code == Codes.DELETE)) {
            val old = k
            cancelLong(p)
            hidePreview()
            p.key = nk
            p.repeated = false
            pressKey(p)
            invalidate(old)
            invalidate(nk)
        }
    }

    private fun up(id: Int, cancel: Boolean) {
        val p = pointers.get(id) ?: return
        pointers.remove(id)
        cancelLong(p)
        p.repeatRunnable?.let { handler.removeCallbacks(it) }
        p.repeatRunnable = null
        hidePreview()
        if (p.swiping) endTrail()

        val k = p.key
        if (k != null && k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            if (deleteSlideActive) {
                if (!cancel && deleteSlideWordCount > 0) {
                    listener?.onDeleteCommit()
                } else {
                    listener?.onDeleteCancel()
                }
                deleteSlideActive = false
                deleteSlideWordCount = 0
                invalidate(k)
            } else if (!cancel && !p.consumed && !p.repeated) {
                listener?.onKey(Codes.DELETE)
            }
        } else if (altShown) {
            val s = altItems.getOrNull(altSel)
            dismissAlt()
            if (!cancel && s != null) listener?.onText(s)
        } else if (!cancel && k != null && !p.consumed && !p.swiping) {
            fire(k)
        }
        if (k != null) invalidate(k)
        recyclePointer(p)
        if (pointers.size() == 0) stopPressAnimation()
    }

    private fun invalidate(k: Key) {
        val r = k.invalidateRect
        if (r != null) invalidate(r) else invalidate()
    }

    private fun cancelLong(p: Pointer) {
        p.longRunnable?.let { handler.removeCallbacks(it) }
        p.longRunnable = null
        p.repeatRunnable?.let { handler.removeCallbacks(it) }
        p.repeatRunnable = null
    }

    private fun fire(k: Key) {
        when (k.kind) {
            KeyKind.CHAR -> listener?.onText(display(k.label))
            KeyKind.SPACE -> listener?.onKey(Codes.SPACE)
            KeyKind.ENTER -> listener?.onKey(Codes.ENTER)
            KeyKind.FUNC -> if (k.code != Codes.DELETE) listener?.onKey(k.code)
            KeyKind.SPACER -> Unit
        }
    }

    private fun longPress(p: Pointer) {
        val k = p.key ?: return
        p.longRunnable = null
        hapticHeavy()
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            p.consumed = true
            return
        }
        // Gboard-style: long-press Enter opens the one-handed mode menu.
        if (k.kind == KeyKind.ENTER) {
            p.consumed = true
            hidePreview()
            listener?.onEnterLongPress()
            return
        }
        if (k.kind == KeyKind.SPACE) {
            if (trackpadEnabled) {
                p.consumed = true
                enterTrackpad()
            } else {
                p.consumed = true
                listener?.onLongPressSpecial()
            }
            return
        }
        if (k.kind == KeyKind.FUNC && k.code == Codes.EMOJI) {
            p.consumed = true
            listener?.onLongPressSpecial()
            return
        }
        if (k.kind == KeyKind.CHAR && k.alts.isNotEmpty()) {
            p.consumed = true
            hidePreview()
            showAlt(p, k)
        }
    }

    // ------------------------------------------------------------------ haptics

    private fun feedback(k: Key) {
        if (haptic) {
            when {
                k.kind == KeyKind.FUNC && k.code == Codes.SHIFT && shiftState == 2 ->
                    hapticHeavy()
                else -> hapticGboard()
            }
        }
        if (sound) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.playSoundEffect(
                when {
                    k.kind == KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                    k.kind == KeyKind.FUNC && k.code == Codes.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                    k.kind == KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }
            )
        }
    }

    private fun hapticGboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_RELEASE,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } else {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    private fun hapticTick() {
        if (!haptic) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                return
            } catch (_: Exception) {}
        }
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun hapticHeavy() {
        if (!haptic) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                return
            } catch (_: Exception) {}
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pressAnimator?.cancel()
        layoutAnimator?.cancel()
        previewAnimator?.cancel()
        altAnimator?.cancel()
        burstAnimator?.cancel()
        trailAnimator?.cancel()
        splitAnimator?.cancel()
        shiftTapAnimator?.cancel()
        oneHandedAnimator?.cancel()
        val n = pointers.size()
        for (i in 0 until n) {
            val p = pointers.valueAt(i)
            cancelLong(p)
            recyclePointer(p)
        }
        pointers.clear()
        previewKey = null
        altShown = false
        altItems = emptyList()
        trailActive = false
        deleteSlideActive = false
    }

    fun release() {
        listener = null
        bgBitmap = null
        pressAnimator?.cancel()
        layoutAnimator?.cancel()
        previewAnimator?.cancel()
        altAnimator?.cancel()
        burstAnimator?.cancel()
        trailAnimator?.cancel()
        splitAnimator?.cancel()
        shiftTapAnimator?.cancel()
        oneHandedAnimator?.cancel()
        pointers.clear()
        pointerPool.clear()
        previewKey = null
        altShown = false
        altItems = emptyList()
        trailActive = false
        deleteSlideActive = false
    }
}
