package com.noircipher.shade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

/** Canvas-drawn keyboard: multi-touch, key previews, long-press accents, space-bar cursor swipe, trackpad mode. */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(code: Int)
        fun onText(text: String)
        fun onCursor(delta: Int)
        fun onCursorMove(dx: Int, dy: Int)
        fun onLongPressSpecial()
        fun onTrackpadEnter()
        fun onTrackpadExit()
    }

    var listener: Listener? = null
    var kbTheme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }
    var rows: List<List<Key>> = emptyList()
        private set
    var contentHeightPx = 0
    var oneHanded = 0
    var haptic = true
    var sound = false
    var showPreview = true
    var shiftState = 0
        set(v) { field = v; invalidate() }
    var enterLabel = ""
        set(v) { field = v; invalidate() }
    var spaceLabel = ""
    var keyBorders = true
    var splitKeyboard = false
    var showHints = true
    var longPressMs = 380L
    var bgBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    // Trackpad
    var trackpadEnabled = true
    private var trackpadActive = false
    private var trackpadStartX = 0f
    private var trackpadStartY = 0f
    private var trackpadLastSentX = 0f
    private var trackpadLastSentY = 0f
    private val trackpadStep = 22f // px per cursor step

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

    private val rect = RectF()
    private val dst = RectF()
    private val path = Path()
    private val clipBounds = Rect()
    private val pressedKeys = HashSet<Key>(16)
    private val cancelIds = IntArray(20)
    private var cancelCount = 0
    private val pointerPool = ArrayDeque<Pointer>(8)
    private var rowHeight = 0f

    private class Pointer {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var longRunnable: Runnable? = null
        var repeatRunnable: Runnable? = null
        var consumed = false
        var swiping = false
        var altShown = false
        var altSel = 0
        var altItems: List<String> = emptyList()
        var altOrigin = 0f
        var altCell = 0f

        fun reset(k: Key?, x: Float, y: Float) {
            key = k; downX = x; downY = y; lastX = x
            longRunnable = null; repeatRunnable = null
            consumed = false; swiping = false; altShown = false
            altSel = 0; altItems = emptyList(); altOrigin = 0f; altCell = 0f
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
        p.altItems = emptyList()
        if (pointerPool.size < 8) pointerPool.offerLast(p)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setRows(r: List<List<Key>>) {
        rows = r
        layoutKeys()
        invalidate()
    }

    fun relayout() { layoutKeys(); invalidate() }

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
        val availW = if (oneHanded == 0) w else w * 0.8f
        val offX = if (oneHanded == 2) w - availW else 0f
        val padX = dp(3f)
        val padTop = dp(5f)
        val padBottom = dp(5f)
        rowHeight = (h - padTop - padBottom) / rows.size
        val gap = if (splitKeyboard) min(dp(72f), w * 0.15f) else 0f
        rows.forEachIndexed { r, row ->
            val eligible = gap > 0f && row.size >= 6 && row.none { it.kind == KeyKind.SPACE }
            val rowGap = if (eligible) gap else 0f
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (availW - 2 * padX - rowGap) / total
            var x = offX + padX
            var cum = 0f
            var gapDone = false
            for (k in row) {
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
            }
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

        // Dim the whole surface slightly in trackpad mode
        val alpha = if (trackpadActive) 0x88 else 0xFF

        for (row in rows) for (k in row) {
            if (k.kind == KeyKind.SPACER) continue
            if (k.right < clipL || k.left > clipR || k.bottom < clipT || k.top > clipB) continue
            rect.set(k.left + gx, k.top + gy, k.right - gx, k.bottom - gy)
            val isPressed = k in pressedKeys
            val special = k.kind == KeyKind.ENTER || (k.code == Codes.SHIFT && shiftState == 2)
            if (!keyBorders && !isPressed && !special) {
                drawContent(c, k)
                continue
            }
            var col = when {
                isPressed -> kbTheme.pressed
                k.kind == KeyKind.ENTER -> kbTheme.accent
                k.kind == KeyKind.FUNC && k.code == Codes.SHIFT && shiftState == 2 -> kbTheme.accent
                k.kind == KeyKind.FUNC -> kbTheme.keyFn
                else -> kbTheme.key
            }
            if (bmp != null && !isPressed) col = ColorUtils.setAlphaComponent(col, 0xCC)
            col = ColorUtils.setAlphaComponent(col, alpha)
            fill.color = col
            c.drawRoundRect(rect, radius, radius, fill)
            drawContent(c, k)
        }
    }

    private fun textY(p: Paint, cy: Float) = cy - (p.ascent() + p.descent()) / 2f

    private fun drawContent(c: Canvas, k: Key) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val s = min(rect.height(), rect.width()) * 0.22f
        when (k.kind) {
            KeyKind.CHAR -> {
                textPaint.color = kbTheme.text
                textPaint.textSize = min(rowHeight * 0.40f, dp(24f))
                c.drawText(display(k.label), cx, textY(textPaint, cy), textPaint)
                if (showHints && k.alts.isNotEmpty() && k.alts[0].isDigit()) {
                    textPaint.color = kbTheme.hint
                    textPaint.textSize = dp(10f)
                    c.drawText(k.alts.substring(0, 1), rect.right - dp(7f), rect.top + dp(12f), textPaint)
                }
            }
            KeyKind.SPACE -> if (spaceLabel.isNotEmpty()) {
                textPaint.color = kbTheme.hint
                textPaint.textSize = dp(12f)
                c.drawText(spaceLabel, cx, textY(textPaint, cy), textPaint)
            }
            KeyKind.ENTER -> {
                if (enterLabel.isNotEmpty()) {
                    textPaint.color = kbTheme.accentText
                    textPaint.textSize = dp(14f)
                    c.drawText(enterLabel, cx, textY(textPaint, cy), textPaint)
                } else {
                    stroke.color = kbTheme.accentText
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
                Codes.SHIFT -> drawShift(c, cx, cy, s)
                Codes.DELETE -> drawDelete(c, cx, cy, s)
                Codes.EMOJI -> drawSmiley(c, cx, cy, s)
                else -> {
                    textPaint.color = kbTheme.text
                    textPaint.textSize = dp(14f)
                    c.drawText(k.label, cx, textY(textPaint, cy), textPaint)
                }
            }
            KeyKind.SPACER -> Unit
        }
    }

    private fun drawShift(c: Canvas, cx: Float, cy0: Float, s: Float) {
        val caps = shiftState == 2
        val cy = if (caps) cy0 - s * 0.15f else cy0
        val col = if (caps) kbTheme.accentText else kbTheme.text
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx + s * 0.45f, cy)
        path.lineTo(cx + s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx - s * 0.45f, cy)
        path.lineTo(cx - s, cy)
        path.close()
        stroke.color = col
        stroke.strokeWidth = dp(2f)
        stroke.style = if (shiftState > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(path, stroke)
        if (caps) {
            stroke.style = Paint.Style.STROKE
            c.drawLine(cx - s * 0.45f, cy + s * 1.3f, cx + s * 0.45f, cy + s * 1.3f, stroke)
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke.color = kbTheme.text
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

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke.color = kbTheme.text
        stroke.strokeWidth = dp(1.8f)
        stroke.style = Paint.Style.STROKE
        c.drawCircle(cx, cy, s * 0.95f, stroke)
        fill.color = kbTheme.text
        c.drawCircle(cx - s * 0.35f, cy - s * 0.25f, s * 0.11f, fill)
        c.drawCircle(cx + s * 0.35f, cy - s * 0.25f, s * 0.11f, fill)
        rect.set(cx - s * 0.5f, cy - s * 0.45f, cx + s * 0.5f, cy + s * 0.5f)
        c.drawArc(rect, 20f, 140f, false, stroke)
    }

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
                if (cancelIds.size < n) return cancelAllSlow()
                for (i in 0 until n) cancelIds[cancelCount++] = pointers.keyAt(i)
                for (i in 0 until cancelCount) up(cancelIds[i], true)
            }
        }
        return true
    }

    private fun handleTrackpad(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackpadStartX = e.x
                trackpadStartY = e.y
                trackpadLastSentX = 0f
                trackpadLastSentY = 0f
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                exitTrackpad()
            }
        }
        return true
    }

    private fun enterTrackpad() {
        if (trackpadActive) return
        trackpadActive = true
        if (haptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onTrackpadEnter()
        invalidate()
    }

    private fun exitTrackpad() {
        if (!trackpadActive) return
        trackpadActive = false
        listener?.onTrackpadExit()
        invalidate()
    }

    private fun cancelAllSlow(): Boolean {
        val n = pointers.size()
        val ids = IntArray(n)
        for (i in 0 until n) ids[i] = pointers.keyAt(i)
        for (id in ids) up(id, true)
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val k = hit(x, y) ?: return
        val p = obtainPointer(k, x, y)
        pointers.put(id, p)
        feedback(k)
        pressKey(p)
        invalidate(k)
    }

    private fun pressKey(p: Pointer) {
        val k = p.key ?: return
        if (k.kind == KeyKind.CHAR) showPreviewFor(k)
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            listener?.onKey(Codes.DELETE)
            val r = object : Runnable {
                var n = 0
                override fun run() {
                    listener?.onKey(Codes.DELETE)
                    n++
                    handler.postDelayed(this, if (n > 10) 35L else 60L)
                }
            }
            p.repeatRunnable = r
            handler.postDelayed(r, 400L)
            return
        }
        val longable = k.kind == KeyKind.SPACE ||
            (k.kind == KeyKind.CHAR && k.alts.isNotEmpty()) ||
            (k.kind == KeyKind.FUNC && k.code == Codes.EMOJI)
        if (longable) {
            val r = Runnable { longPress(p) }
            p.longRunnable = r
            handler.postDelayed(r, longPressMs)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        val k = p.key ?: return
        if (p.altShown) {
            p.lastX = x
            updateAlt(p, x)
            return
        }
        if (k.kind == KeyKind.SPACE) {
            if (p.consumed) return
            if (!p.swiping && abs(x - p.downX) > dp(14f)) {
                p.swiping = true
                cancelLong(p)
                p.lastX = x
            }
            if (p.swiping) {
                val step = dp(14f)
                while (x - p.lastX >= step) { listener?.onCursor(1); p.lastX += step }
                while (p.lastX - x >= step) { listener?.onCursor(-1); p.lastX -= step }
            }
            return
        }
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) return
        val nk = hit(x, y)
        if (nk != null && nk !== k && !(nk.kind == KeyKind.FUNC && nk.code == Codes.DELETE)) {
            val old = k
            cancelLong(p)
            hidePreview()
            p.key = nk
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
        val k = p.key
        if (p.altShown) {
            val s = p.altItems.getOrNull(p.altSel)
            dismissAlt()
            if (!cancel && s != null) listener?.onText(s)
        } else if (!cancel && k != null && !p.consumed && !p.swiping) {
            fire(k)
        }
        if (k != null) invalidate(k)
        recyclePointer(p)
    }

    private fun invalidate(k: Key) {
        val r = k.invalidateRect
        if (r != null) invalidate(r) else invalidate()
    }

    private fun cancelLong(p: Pointer) {
        p.longRunnable?.let { handler.removeCallbacks(it) }
        p.longRunnable = null
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
        if (haptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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

    @Suppress("DEPRECATION")
    private fun feedback(k: Key) {
        if (haptic) {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
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

    private val previewText = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
    }
    private var previewWindow: PopupWindow? = null
    private val loc = IntArray(2)

    private inner class AltView(ctx: Context) : View(ctx) {
        var items: List<String> = emptyList()
        var sel = 0
        var cell = 0f
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val r = dp(10f)
            bg.color = kbTheme.popupBg
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
            items.forEachIndexed { i, s ->
                val l = i * cell
                if (i == sel) {
                    bg.color = kbTheme.accent
                    canvas.drawRoundRect(l + dp(3f), dp(3f), l + cell - dp(3f), height - dp(3f), r, r, bg)
                }
                tp.color = if (i == sel) kbTheme.accentText else kbTheme.text
                tp.textSize = dp(22f)
                canvas.drawText(s, l + cell / 2f, height / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
            }
        }
    }

    private val altView = AltView(context)
    private var altWindow: PopupWindow? = null

    private fun showPreviewFor(k: Key) {
        if (!showPreview) return
        previewText.background = GradientDrawableCache.forKey(kbTheme.popupBg, dp(10f))
        previewText.setTextColor(kbTheme.text)
        previewText.text = display(k.label)
        val pw = (k.right - k.left + dp(18f)).toInt()
        val ph = dp(58f).toInt()
        getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + k.centerX - pw / 2f).toInt().coerceIn(0, maxOf(0, screenW - pw))
        val y = (loc[1] + k.top - ph - dp(2f)).toInt()
        try {
            val w = previewWindow
            if (w == null) {
                previewWindow = PopupWindow(previewText, pw, ph).apply {
                    isTouchable = false
                    isFocusable = false
                    isClippingEnabled = false
                    inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                    showAtLocation(this@KeyboardView, Gravity.NO_GRAVITY, x, y)
                }
            } else {
                w.update(x, y, pw, ph)
            }
        } catch (_: Exception) {
            previewWindow = null
        }
    }

    private fun hidePreview() {
        try { previewWindow?.dismiss() } catch (_: Exception) {}
        previewWindow = null
    }

    private fun showAlt(p: Pointer, k: Key) {
        val chars = k.alts.map { display(it.toString()) }.distinct()
        val n = chars.size
        val cell = min(dp(44f), (width - dp(8f)) / n)
        val pw = (cell * n).toInt()
        val ph = dp(54f).toInt()
        val leftMode = k.centerX < width / 2f
        val origin = (if (leftMode) k.left else k.right - pw)
            .coerceIn(0f, maxOf(0f, (width - pw).toFloat()))
        p.altItems = if (leftMode) chars else chars.reversed()
        p.altOrigin = origin
        p.altCell = cell
        p.altShown = true
        p.altSel = ((p.lastX - origin) / cell).toInt().coerceIn(0, n - 1)
        altView.items = p.altItems
        altView.sel = p.altSel
        altView.cell = cell
        getLocationOnScreen(loc)
        val px = loc[0] + origin.toInt()
        val py = (loc[1] + k.top - ph - dp(6f)).toInt()
        try {
            altWindow = PopupWindow(altView, pw, ph).apply {
                isTouchable = false
                isFocusable = false
                isClippingEnabled = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                showAtLocation(this@KeyboardView, Gravity.NO_GRAVITY, px, py)
            }
        } catch (_: Exception) {
            altWindow = null
        }
        altView.invalidate()
    }

    private fun updateAlt(p: Pointer, x: Float) {
        val idx = ((x - p.altOrigin) / p.altCell).toInt().coerceIn(0, p.altItems.size - 1)
        if (idx != p.altSel) {
            p.altSel = idx
            altView.sel = idx
            altView.invalidate()
        }
    }

    private fun dismissAlt() {
        try { altWindow?.dismiss() } catch (_: Exception) {}
        altWindow = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val n = pointers.size()
        for (i in 0 until n) {
            val p = pointers.valueAt(i)
            cancelLong(p)
            p.repeatRunnable?.let { handler.removeCallbacks(it) }
            recyclePointer(p)
        }
        pointers.clear()
        hidePreview()
        dismissAlt()
    }

    fun release() {
        listener = null
        bgBitmap = null
        pointers.clear()
        pointerPool.clear()
        previewWindow?.let { try { it.dismiss() } catch (_: Exception) {} }
        altWindow?.let { try { it.dismiss() } catch (_: Exception) {} }
        previewWindow = null
        altWindow = null
    }
}
