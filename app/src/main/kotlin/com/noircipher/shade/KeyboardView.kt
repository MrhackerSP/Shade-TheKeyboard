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
import androidx.core.graphics.ColorUtils
import com.noircipher.shade.motion.ShadeMotion
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

/** Finger speed (dp per ms) above which a slide on delete selects word by word instead of character by character. */
private const val DELETE_FAST_DP_PER_MS = 0.55f

class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(code: Int)
        fun onText(text: String)
        /**
         * Accent variant selected from a long-press popup.
         *
         * @param base   the displayed label of the key that was long-pressed (already case-shifted
         *               for the current shift state). This is what the IME should compare against
         *               the trailing char of the composing region to decide whether to swap
         *               (atomic replaceLast) or append.
         * @param accent the chosen accented character (already case-shifted).
         *
         * Default impl falls back to [onText] for listeners that don’t care about the swap.
         */
        fun onAccent(base: String, accent: String) { onText(accent) }
        fun onCursor(delta: Int)
        fun onCursorMove(dx: Int, dy: Int)
        fun onLongPressSpecial()
        fun onEnterLongPress()
        fun onTrackpadEnter()
        fun onTrackpadExit()
        fun onDeleteWords(count: Int)
        /** A slide on the delete key has begun: remember the caret and start selecting backwards. */
        fun onDeleteSlideStart()
        /**
         * Move the selection edge by [units] steps (positive = further left, negative = back
         * towards the caret). A step is one word when [byWord] (fast slide) and one character
         * otherwise (slow slide). Returns the number of characters now selected.
         */
        fun onDeleteSlideStep(units: Int, byWord: Boolean): Int
        fun onDeleteCommit()
        fun onDeleteCancel()
        fun onSwipePath(path: List<FloatArray>): Boolean = false
        fun onTouchSample(key: Key, x: Float, y: Float) {}
        /** Glide typing live preview — called while the finger is still moving. */
        fun onGlidePreview(word: String?) {}
        /** Glide typing final commit — called on finger up. */
        fun onGlideCommit(word: String) {}
        /** Called when a word is committed (space/punct) with per-character touch offsets. */
        fun onTouchOffsets(offsets: List<TouchGeometry.KeyTouch>) {}
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
    /** 0 = light, 1 = medium (default, system keyboard tap), 2 = strong. */
    var hapticStrength = 1
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
        set(v) {
            // The owner (ShadeService) is the only one that recycles; the view just drops the reference.
            field = v
            invalidate()
        }

    var trackpadEnabled = true
    /** Glide (swipe) typing: when off, swipes behave like plain taps. */
    var glideEnabled = true
    private var trackpadActive = false
    private var trackpadStartX = 0f
    private var trackpadStartY = 0f
    private var trackpadLastSentX = 0f
    private var trackpadLastSentY = 0f
    private val trackpadStep by lazy { dp(7f) }  // ~22 px on xxhdpi, density-aware

    private var deleteSlideActive = false
    private var deleteSlideStartX = 0f
    private var deleteSlideWordCount = 0 // characters currently selected by the slide
    private var deleteSlideLastX = 0f
    private var deleteSlideLastT = 0L
    private var deleteSlideAccum = 0f
    private var deleteSlideSpeed = 0f // smoothed finger speed in dp per millisecond

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
    // Shared popup layer: every key preview and accent grid (all rows) is drawn here, above the whole
    // keyboard window. The in-view drawing below is only a fallback if the layer can't attach.
    private val popupLayer = KeyPopupOverlay(this) { kbTheme }
    private var popupActive = false
    private var altUsesOverlay = false

    private var altShown = false
    private var altItems: List<String> = emptyList()
    private var altSel = 0
    private var altCols = 1
    private var altCell = 0f
    private var altOriginX = 0f
    private var altTop = 0f
    private var altProgress = 0f
    private var altAnimator: ValueAnimator? = null
    private var altBaseLabel = ""   // raw label of the long-pressed key (for onAccent)
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

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private class Pointer {
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var longRunnable: Runnable? = null
        var repeatRunnable: Runnable? = null
        var consumed = false
        var swiping = false
        var repeated = false

        fun reset(k: Key?, x: Float, y: Float) {
            key = k; downX = x; downY = y; lastX = x; lastY = y
            longRunnable = null; repeatRunnable = null
            consumed = false; swiping = false; repeated = false
        }
    }

    private val pointers = SparseArray<Pointer>()
    private var slidePointerId = -1
    private val slidePath = ArrayDeque<FloatArray>()
    // Touch geometry: per-character offsets for the word being typed
    private val touchOffsets = ArrayList<TouchGeometry.KeyTouch>()
    private var gestureEngine: GestureEngine? = null
    private var gestureStartTime = 0L
    private var lastGlidePreviewMs = 0L

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
            duration = ShadeMotion.KEY_POPUP_OUT_MS
            interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
            addUpdateListener { layoutAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rows = r
                    layoutKeys()
                    layoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = ShadeMotion.LAYOUT_FADE_IN_MS
                        interpolator = ShadeMotion.FAST_OUT_SLOW_IN
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
            duration = ShadeMotion.MODE_CHANGE_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener {
                splitProgress = it.animatedValue as Float
                // TODO(perf): layoutKeys() runs every frame (~11 passes over ~40 keys).
                // Acceptable for a one-time user-initiated toggle, but could precompute
                // start/end layouts and interpolate offsets if this becomes hot.
                layoutKeys()
                invalidate()
            }
            start()
        }
    }

    fun setOneHandedAnimated(side: Int) {
        oneHanded = side
        val target = if (side == 0) 1f else 0.75f
        if (oneHandedProgress == target) return
        oneHandedAnimator?.cancel()
        oneHandedAnimator = ValueAnimator.ofFloat(oneHandedProgress, target).apply {
            duration = ShadeMotion.MODE_CHANGE_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener {
                oneHandedProgress = it.animatedValue as Float
                // TODO(perf): layoutKeys() runs every frame (~11 passes over ~40 keys).
                // Acceptable for a one-time user-initiated toggle, but could precompute
                // start/end layouts and interpolate offsets if this becomes hot.
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
        // Edge to edge: with gesture navigation the system reserves a strip at each screen edge for
        // "back". Claim those strips for the keyboard so the outermost keys (q/p, a/l, ...) can be
        // pressed right up to the screen edge.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val edge = dp(40f).toInt()
            systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, 0, edge, h),
                android.graphics.Rect(w - edge, 0, w, h)
            )
        }
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || rows.isEmpty()) return

        val availW = w * oneHandedProgress
        val offX = when (oneHanded) {
            1 -> 0f
            2 -> w - availW
            else -> 0f
        }

        // Reduced top and bottom padding so the bottom row hugs the edge.
        val padTop = dp(5f)
        val padBottom = dp(1f)
        val bottomRowPad = dp(2f)
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

        // Bottom-row outer corners: only the first and last *functional*
        // keys (not spacers) get a corner treatment so ?123 and Enter hug
        // the screen edge, matching modern keyboard.
        val lastRow = rows.lastOrNull() ?: return
        val visible = lastRow.filter { it.kind != KeyKind.SPACER }
        for (k in visible) k.outerCorner = KeyOuterCorner.NONE
        if (visible.isNotEmpty()) {
            visible.firstOrNull()?.outerCorner = KeyOuterCorner.BOTTOM_LEFT
            visible.last().outerCorner = KeyOuterCorner.BOTTOM_RIGHT
        }
    }

    private fun hit(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        // Find the row whose vertical span is closest to y — manual loop, no allocation.
        var bestRow = -1
        var bestRowDist = Float.MAX_VALUE
        for (i in rows.indices) {
            val k = rows[i][0]
            val d = when {
                y < k.top -> k.top - y
                y > k.bottom -> y - k.bottom
                else -> 0f
            }
            if (d < bestRowDist) {
                bestRowDist = d
                bestRow = i
            }
        }
        if (bestRow < 0) return null
        // Find the key in that row whose horizontal span is closest to x.
        var bestKey: Key? = null
        var bestKeyDist = Float.MAX_VALUE
        for (k in rows[bestRow]) {
            if (k.kind == KeyKind.SPACER) continue
            val d = when {
                x < k.left -> k.left - x
                x > k.right -> x - k.right
                else -> 0f
            }
            if (d < bestKeyDist) {
                bestKeyDist = d
                bestKey = k
            }
        }
        return bestKey
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
        val bmp = bgBitmap?.takeIf { !it.isRecycled }
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
        val outerRadius = dp(24f)
        val combinedAlpha = ((if (trackpadActive) 0x88 else 0xFF) * layoutAlpha).toInt().coerceIn(0, 255)
        val pressDown = dp(0.7f)

        for (row in rows) for (k in row) {
            if (k.kind == KeyKind.SPACER) continue
            if (k.right < clipL || k.left > clipR || k.bottom < clipT || k.top > clipB) continue
            val pressAmt = k.pressAmt
            val isPressed = pressAmt > 0.003f
            val topShift = pressDown * pressAmt
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
                if (k.outerCorner == KeyOuterCorner.NONE) {
                    c.drawRoundRect(rect, radius, radius, shadowPaint)
                } else {
                    c.drawRoundRect(rect, outerRadius, outerRadius, shadowPaint)
                }
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
                ColorUtils.blendARGB(baseCol, kbTheme.pressed, pressAmt)
            } else {
                baseCol
            }
            fill.color = ColorUtils.setAlphaComponent(col, combinedAlpha)

            val outer = k.outerCorner
            if (outer == KeyOuterCorner.NONE) {
                c.drawRoundRect(rect, radius, radius, fill)
            } else {
                drawOuterRoundedKey(c, k, radius, outerRadius, outer)
            }
            drawContent(c, k, combinedAlpha)

            if (k.code == Codes.DELETE && deleteSlideActive && deleteSlideWordCount > 0) {
                drawDeleteSlideBadge(c, k)
            }
        }

        if (trailActive && trailAlpha > 0f) drawTrail(c)
        if (burstActive && typingBurstEnabled) drawTypingBurst(c)
        if (altShown && altItems.isNotEmpty() && !altUsesOverlay) drawAltStrip(c)
        previewKey?.let { if (previewProgress > 0f && !popupActive) drawKeyPreview(c, it) }
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

    /**
     * Draw a key whose outer bottom corner (left or right) is rounded with
     * [outerRadius] while the other three corners use the regular [radius].
     * Used for the ?123 key (bottom-left) and Enter key (bottom-right) so
     * they hug the screen edge the way modern keyboard's do.
     *
     * The radii array follows the order expected by Path.addRoundRect:
     *   [topLeftX, topLeftY, topRightX, topRightY,
     *    bottomRightX, bottomRightY, bottomLeftX, bottomLeftY]
     */
    private fun drawOuterRoundedKey(
        c: Canvas,
        k: Key,
        radius: Float,
        outerRadius: Float,
        which: KeyOuterCorner
    ) {
        // 8 radii: [tl_x, tl_y, tr_x, tr_y, br_x, br_y, bl_x, bl_y]
        val r = FloatArray(8) { radius }
        when (which) {
            KeyOuterCorner.BOTTOM_LEFT -> {
                r[6] = outerRadius; r[7] = outerRadius   // bottom-left
            }
            KeyOuterCorner.BOTTOM_RIGHT -> {
                r[4] = outerRadius; r[5] = outerRadius   // bottom-right
            }
            KeyOuterCorner.NONE -> { /* all radius */ }
        }
        path.reset()
        path.addRoundRect(rect, r, Path.Direction.CW)
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
        val cy = if (caps) cy0 - s * 0.15f else cy0
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
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = dp(2f)
            stroke.color = ColorUtils.setAlphaComponent(col, alpha)
            c.drawLine(cx - s * 0.5f, cy + s * 1.4f, cx + s * 0.5f, cy + s * 1.4f, stroke)
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
        // Draw the basic space-swipe trail (cursor movement)
        val a = trailAlpha
        if (a > 0f) {
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

        // Draw the gesture engine trail (glide typing) with fade + width taper
        val ge = gestureEngine
        if (ge != null) {
            ge.setTrailColor(ColorUtils.setAlphaComponent(kbTheme.accent, 0x80))
            ge.drawTrail(c, trailPaint)
            // Schedule redraw for trail animation
            if (ge.hasTrail()) invalidate()
        }
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
            duration = ShadeMotion.SWIPE_TRAIL_FADE_IN_MS
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
            duration = ShadeMotion.SWIPE_TRAIL_FADE_OUT_MS
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
        val t = previewProgress.coerceIn(0f, 1f)
        val scale = 0.82f + 0.18f * t
        val w = (k.right - k.left) * 1.3f * scale
        val h = rowHeight * 1.25f * scale
        val cx = k.centerX.coerceIn(w / 2f + dp(2f), width - w / 2f - dp(2f))
        var bottom = k.top + dp(6f)
        var top = bottom - h
        if (top < dp(1f)) {
            bottom += dp(1f) - top
            top = dp(1f)
        }
        val r = dp(14f)
        val a = (255 * minOf(1f, t * 1.6f)).toInt()

        rect.set(cx - w / 2f, top + dp(2f), cx + w / 2f, bottom + dp(2f))
        previewShadow.color = ColorUtils.setAlphaComponent(0x000000, (0x40 * t).toInt())
        c.drawRoundRect(rect, r, r, previewShadow)

        rect.set(cx - w / 2f, top, cx + w / 2f, bottom)
        previewBg.color = ColorUtils.setAlphaComponent(kbTheme.popupBg, a)
        c.drawRoundRect(rect, r, r, previewBg)

        previewText.color = ColorUtils.setAlphaComponent(kbTheme.text, a)
        previewText.textSize = min(h * 0.5f, dp(32f))
        c.drawText(label, cx, (top + bottom) / 2f - (previewText.ascent() + previewText.descent()) / 2f, previewText)
    }

    private fun showPreviewFor(k: Key) {
        if (!showPreview) return
        if (popupLayer.prepare()) {
            // Same bubble + animation for every row, drawn above everything in the keyboard window.
            previewKey = null
            popupActive = true
            popupLayer.showPreview(display(k.label), k.left, k.top, k.right, rowHeight)
            return
        }
        // Fallback: draw inside the keyboard view (cannot extend above it).
        popupActive = false
        previewKey = k
        previewAnimator?.cancel()
        previewAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.KEY_POPUP_IN_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener { previewProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun hidePreview() {
        if (popupActive) {
            popupActive = false
            popupLayer.hidePreview()
            return
        }
        if (previewKey == null) return
        previewAnimator?.cancel()
        previewAnimator = ValueAnimator.ofFloat(previewProgress, 0f).apply {
            duration = ShadeMotion.KEY_POPUP_OUT_MS
            interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
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

    /**
     * In-view fallback for the accent strip. Same geometry as the overlay path, but bounded by
     * the keyboard view itself — used only when [KeyPopupOverlay.prepare] fails.
     */
    private fun drawAltStrip(c: Canvas) {
        val n = altItems.size
        if (n == 0) return
        val scale = 0.75f + 0.25f * altProgress
        val cell = altCell * scale
        val stripH = cell
        val stripW = cell * n
        val left = altOriginX
        val top = altTop + (altCell - stripH)

        altRect.set(left, top + dp(3f), left + stripW, top + dp(3f) + stripH)
        altShadow.color = 0x55000000
        val r = dp(16f) * scale
        c.drawRoundRect(altRect, r, r, altShadow)

        altRect.set(left, top, left + stripW, top + stripH)
        altBg.color = kbTheme.popupBg
        c.drawRoundRect(altRect, r, r, altBg)

        // Selected cell pops up by dp(4) * progress — same look as the overlay strip.
        val elev = dp(4f) * altProgress
        val col = altSel.coerceIn(0, n - 1)
        val selLeft = left + col * cell + dp(4f) * scale
        val selRight = left + (col + 1) * cell - dp(4f) * scale
        val selTop = top - elev + dp(4f) * scale
        val selBottom = top - elev + cell - dp(4f) * scale
        altRect.set(selLeft, selTop, selRight, selBottom)
        altSelPaint.color = kbTheme.accent
        c.drawRoundRect(altRect, r * 0.75f, r * 0.75f, altSelPaint)

        altText.textSize = min(cell * 0.55f, dp(22f) * scale)
        for (i in 0 until n) {
            val ccol = i % n
            altText.color = if (i == altSel) kbTheme.accentText else kbTheme.text
            val yOff = if (i == altSel) -elev else 0f
            val cx = left + ccol * cell + cell / 2f
            val cy = top + yOff + cell / 2f - (altText.ascent() + altText.descent()) / 2f
            c.drawText(altItems[i].toString(), cx, cy, altText)
        }
    }

    private fun showAlt(p: Pointer, k: Key) {
        // Display-shift the alts (so long-pressing 'a' with shift on shows 'Á'), then drop duplicates.
        // Cap at MAX_STRIP_ITEMS — single horizontal row, no second row.
        val chars = k.alts.map { display(it.toString()) }
            .distinct()
            .take(KeyPopupOverlay.MAX_STRIP_ITEMS)
        if (chars.isEmpty()) return
        val n = chars.size
        altCols = n
        val cell = min(dp(52f), (width - dp(8f)) / n)
        val pw = cell * n
        val ph = cell

        val keyCenter = k.centerX
        val origin = (keyCenter - pw / 2f)
            .coerceIn(0f, maxOf(0f, (width - pw).toFloat()))

        // The shared popup layer can reach up into the suggestion strip, so top-row strips stay visible.
        val useOverlay = popupLayer.prepare()
        val minTop = if (useOverlay) popupLayer.minTopInOwner() else dp(2f)

        altItems = chars
        altBaseLabel = k.label
        altOriginX = origin
        altCell = cell
        altTop = (k.top - dp(6f) - ph).coerceAtLeast(minTop)
        altShown = true
        // Long-press + release without sliding lands on the number printed on a top-row key
        // (q -> 1, w -> 2 ...). Keys without a number hint default to the middle of the strip,
        // the "primary" variant. Sliding still reaches every accent.
        val numberIdx = chars.indexOfFirst { it.length == 1 && it[0].isDigit() }
        altSel = if (numberIdx >= 0) numberIdx else (n / 2).coerceIn(0, n - 1)

        altAnimator?.cancel()
        if (useOverlay) {
            altUsesOverlay = true
            altProgress = 1f
            popupLayer.showAccents(chars, cell, origin, altTop, altSel)
        } else {
            altUsesOverlay = false
            altAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ShadeMotion.KEY_POPUP_IN_MS
                interpolator = ShadeMotion.FAST_OUT_SLOW_IN
                addUpdateListener { altProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    private fun updateAlt(p: Pointer, x: Float, y: Float) {
        if (!altShown || altItems.isEmpty()) return
        // Single-row strip: only the column matters.
        val col = ((x - altOriginX) / altCell).toInt().coerceIn(0, altItems.size - 1)
        if (col != altSel) {
            altSel = col
            if (altUsesOverlay) popupLayer.setAccentSelection(col)
            hapticTick()
            invalidate()
        }
    }

    private fun dismissAlt() {
        if (!altShown) return
        altAnimator?.cancel()
        if (altUsesOverlay) {
            // The layer keeps its own copy of the grid and animates it out.
            popupLayer.hideAccents()
            altUsesOverlay = false
            altShown = false
            altItems = emptyList()
            return
        }
        altAnimator = ValueAnimator.ofFloat(altProgress, 0f).apply {
            duration = ShadeMotion.KEY_POPUP_OUT_MS
            interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
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
                duration = ShadeMotion.FEEDBACK_BURST_MS
                interpolator = ShadeMotion.FAST_OUT_SLOW_IN
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
                // Deliver touch events as they arrive instead of batching them to the
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    try { requestUnbufferedDispatch(e) } catch (_: Throwable) {}
                }
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
        if (k.kind == KeyKind.CHAR) {
            listener?.onTouchSample(k, x, y)

            // Touch geometry: record the offset from key center
            val offset = TouchGeometry.computeTouchOffset(k, x, y)
            if (offset != null) touchOffsets.add(offset)

            if (glideEnabled) {
                slidePointerId = id
                slidePath.clear()
                slidePath.addLast(floatArrayOf(x, y))

                // Initialize gesture engine on first key down
                val keyW = if (rows.isNotEmpty() && rows[0].isNotEmpty()) {
                    rows[0][0].right - rows[0][0].left
                } else 100f
                val ge0 = gestureEngine
                if (ge0 == null) gestureEngine = GestureEngine(keyW) else ge0.setKeyWidth(keyW)
                gestureStartTime = android.os.SystemClock.uptimeMillis()
                gestureEngine?.onDown(x, y, 0)
            }
        }
        feedback(k)
        onKeyBurst()
        touchKey(k)
        pressKey(p)
        invalidate(k)
    }

    // ---- Per-key press feedback -------------------------------------------------------------
    // Every key has its own pressAmt that eases towards 1 while held (fast) and back to 0 after
    // release (slower), so a key fades out instead of snapping back, and two keys pressed in
    // quick succession animate independently. Driven by Choreographer so it follows the display.
    private val animKeys = ArrayList<Key>(8)
    private var pressTickRunning = false
    private var pressTickLastNanos = 0L

    private val pressTick = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtMs = if (pressTickLastNanos == 0L) 16f
            else ((frameTimeNanos - pressTickLastNanos) / 1_000_000f).coerceIn(1f, 50f)
            pressTickLastNanos = frameTimeNanos
            var moving = false
            var i = animKeys.size - 1
            while (i >= 0) {
                val k = animKeys[i]
                val target = if (isHeld(k)) 1f else 0f
                val cur = k.pressAmt
                if (cur != target) {
                    k.pressAmt = if (target > cur) {
                        minOf(1f, cur + dtMs / ShadeMotion.KEY_PRESS_DOWN_MS)
                    } else {
                        maxOf(0f, cur - dtMs / ShadeMotion.KEY_PRESS_UP_MS)
                    }
                    invalidate(k)
                    if (k.pressAmt != target) moving = true
                }
                if (k.pressAmt == 0f && target == 0f) animKeys.removeAt(i)
                i--
            }
            if (moving) {
                android.view.Choreographer.getInstance().postFrameCallback(this)
            } else {
                pressTickRunning = false
                pressTickLastNanos = 0L
            }
        }
    }

    private fun isHeld(k: Key): Boolean {
        for (i in 0 until pointers.size()) if (pointers.valueAt(i).key === k) return true
        return false
    }

    private fun touchKey(k: Key) {
        if (!animKeys.contains(k)) animKeys.add(k)
        kickPressTick()
    }

    private fun kickPressTick() {
        if (pressTickRunning) return
        pressTickRunning = true
        pressTickLastNanos = 0L
        android.view.Choreographer.getInstance().postFrameCallback(pressTick)
    }

    private fun cancelPressTick() {
        android.view.Choreographer.getInstance().removeFrameCallback(pressTick)
        pressTickRunning = false
        pressTickLastNanos = 0L
        for (k in animKeys) k.pressAmt = 0f
        animKeys.clear()
    }

    private fun startPressAnimation() { kickPressTick() }

    private fun stopPressAnimation() { kickPressTick() }

    private fun animateShiftTap() {
        shiftTapAnimator?.cancel()
        shiftTapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.KEY_POPUP_IN_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            // TODO(perf): shiftTapProgress only affects the shift key's drawing; the
            // whole-view invalidate() here could be replaced with a dirty rect covering
            // the shift key's invalidateRect. Skipped for now — animator is short.
            addUpdateListener {
                shiftTapProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shiftTapAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = ShadeMotion.SWIPE_TRAIL_FADE_OUT_MS
                        interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
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
            var n = 0
            override fun run() {
                if (p.consumed) return
                if (p.key !== k) return
                listener?.onKey(Codes.DELETE)
                n++
                if (haptic && n % 2 == 0) hapticTick()
                val next = if (n > 12) (repeatIntervalMs / 2).coerceAtLeast(18L) else repeatIntervalMs
                handler.postDelayed(this, next)
            }
        }
        p.repeatRunnable = repeat
        handler.postDelayed(repeat, repeatDelayMs)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        val k = p.key ?: return
        // Accent popup takes priority over everything: once the long-press popup is open, the
        // finger only navigates the accent strip. We must NOT feed the gesture engine or the
        // slide path here, otherwise the glide-typing system records the slide-over-accents as a
        // glide word and commits it on finger-up (inserting “letters behind the popup”).
        if (altShown) {
            p.lastX = x
            p.lastY = y
            updateAlt(p, x, y)
            return
        }
        if (id == slidePointerId && k.kind == KeyKind.CHAR) {
            slidePath.addLast(floatArrayOf(x, y))
            if (slidePath.size > 240) slidePath.removeFirst()

            // Feed gesture engine for proper sampling + trail
            val elapsed = (android.os.SystemClock.uptimeMillis() - gestureStartTime).toInt()
            gestureEngine?.addEventPoint(x, y, elapsed)

            // Glide live preview using the gesture engine's sampled path
            val sampledPath = gestureEngine?.getIncrementalPath()
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (sampledPath != null && sampledPath.size >= 6 &&
                gestureEngine?.hasRecognitionTimePast(nowMs, lastGlidePreviewMs) == true) {
                lastGlidePreviewMs = nowMs
                listener?.onGlidePreview(slidePathToWord(sampledPath))
            }

            // Draw trail via gesture engine
            updateTrail(x, y)
        }
        if (k.kind == KeyKind.SPACE) {
            if (p.consumed) return
            if (!p.swiping && abs(x - p.downX) > dp(14f)) {
                p.swiping = true
                cancelLong(p)
                hidePreview()
                p.lastX = x
                p.lastY = y
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
            val now = android.os.SystemClock.uptimeMillis()
            if (!deleteSlideActive && dx < -dp(16f)) {
                deleteSlideActive = true
                deleteSlideStartX = p.downX
                deleteSlideWordCount = 0
                deleteSlideLastX = x
                deleteSlideLastT = now
                deleteSlideAccum = 0f
                deleteSlideSpeed = 0f
                cancelLong(p)
                hapticTick()
                listener?.onDeleteSlideStart()
                // The distance already travelled to cross the threshold counts as the first steps.
                deleteSlideAccum = -dx - dp(16f)
            } else if (deleteSlideActive) {
                // Speed picks the unit: a slow drag selects character by character, a fast flick
                // word by word. The speed is smoothed so one jittery sample can't flip the mode.
                val moved = deleteSlideLastX - x // positive = moving left
                val dt = (now - deleteSlideLastT).coerceAtLeast(1L)
                val instant = abs(moved) / dp(1f) / dt
                deleteSlideSpeed = deleteSlideSpeed * 0.6f + instant * 0.4f
                deleteSlideLastX = x
                deleteSlideLastT = now
                deleteSlideAccum += moved
            }
            if (deleteSlideActive) {
                val fast = deleteSlideSpeed >= DELETE_FAST_DP_PER_MS
                val stepPx = if (fast) dp(22f) else dp(9f)
                var units = 0
                while (deleteSlideAccum >= stepPx) { units++; deleteSlideAccum -= stepPx }
                while (deleteSlideAccum <= -stepPx) { units--; deleteSlideAccum += stepPx }
                if (units != 0) {
                    val selected = listener?.onDeleteSlideStep(units, fast) ?: 0
                    if (selected != deleteSlideWordCount) {
                        deleteSlideWordCount = selected
                        hapticTick()
                        invalidate()
                    }
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
            touchKey(nk)
            pressKey(p)
            invalidate(old)
            invalidate(nk)
        }
    }


    private fun slidePathSpread(path: Collection<FloatArray>): Float {
        if (path.isEmpty()) return 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in path) {
            minX = minOf(minX, point[0]); maxX = maxOf(maxX, point[0])
            minY = minOf(minY, point[1]); maxY = maxOf(maxY, point[1])
        }
        return (maxX - minX) + (maxY - minY)
    }

    /** Quick raw sequence from slide path — for live preview only. */
    private fun slidePathToWord(path: List<FloatArray>): String? {
        if (path.size < 4) return null
        val out = StringBuilder()
        var last = ' '
        for (point in path) {
            val x = point[0]
            val y = point[1]
            var best: Key? = null
            var bestDist = Float.MAX_VALUE
            for (row in rows) {
                for (k in row) {
                    if (k.kind != KeyKind.CHAR || k.label.length != 1) continue
                    val cx = (k.left + k.right) / 2f
                    val cy = (k.top + k.bottom) / 2f
                    val dx = x - cx
                    val dy = y - cy
                    val dist = dx * dx + dy * dy
                    if (dist < bestDist) {
                        bestDist = dist
                        best = k
                    }
                }
            }
            // Same crossing gate as the decoder: preview must show crossed keys
            // only, not every key the swipe happened to pass nearest to.
            val k = best
            if (k != null && bestDist <= slideKeyThreshold(k) * slideKeyThreshold(k)) {
                val c = k.label[0].lowercaseChar()
                if (c != last) {
                    out.append(c)
                    last = c
                }
            }
        }
        return out.toString().ifEmpty { null }
    }

    /** Max distance from a key center that still counts as crossing the key (0.4 key sizes). */
    private fun slideKeyThreshold(k: Key): Float {
        val w = (k.right - k.left).coerceAtLeast(1f)
        val h = (k.bottom - k.top).coerceAtLeast(1f)
        return 0.4f * minOf(w, h)
    }

    private fun up(id: Int, cancel: Boolean) {
        val p = pointers.get(id) ?: return
        pointers.remove(id)
        cancelLong(p)
        p.repeatRunnable?.let { handler.removeCallbacks(it) }
        p.repeatRunnable = null
        hidePreview()
        if (p.swiping) endTrail()

        var slideConsumed = false
        // Don't commit a glide word if the finger ended on an accent selection — the accent
        // branch below handles that. Without this guard, a slide that started before the
        // long-press fired (recording a few glide points) would also commit a glide word.
        if (!cancel && !altShown && id == slidePointerId && slidePath.size >= 4 && slidePathSpread(slidePath) > dp(48f)) {
            // Gesture typing: use the gesture engine's properly sampled path
            val path = gestureEngine?.getPath() ?: slidePath.toList()
            slideConsumed = listener?.onSwipePath(path) == true
            if (slideConsumed) p.consumed = true
            // Add trail points and clear the live preview — also when decoding
            // failed, otherwise the raw preview letters linger as composing text.
            gestureEngine?.addTrail()
            listener?.onGlidePreview(null)
        }
        if (id == slidePointerId) {
            slidePointerId = -1
            slidePath.clear()
        }

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
        stopPressAnimation() // lets the released key fade out, even while another finger is down
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
        if (k.kind == KeyKind.CHAR) hapticTick() else hapticHeavy()
        if (k.kind == KeyKind.FUNC && k.code == Codes.DELETE) {
            p.consumed = true
            return
        }
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
            // Don't call hidePreview() — showAlt() will morph the existing
            showAlt(p, k)
        }
    }

    // ------------------------------------------------------------------ haptics

    private fun feedback(k: Key) {
        if (haptic) {
            when {
                k.kind == KeyKind.FUNC && k.code == Codes.SHIFT && shiftState == 2 ->
                    hapticHeavy()
                else -> hapticModern()
            }
        }
        if (sound) {
            val am = audioManager
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

    private fun hapticModern() {
        val played = when (hapticStrength) {
            0 -> playPredefinedHaptic(VibrationEffect.EFFECT_TICK)
            2 -> playPredefinedHaptic(VibrationEffect.EFFECT_CLICK)
            else -> false
        }
        if (played) return
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun playPredefinedHaptic(effect: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val v = vibrator ?: return false
        return try {
            v.vibrate(VibrationEffect.createPredefined(effect))
            true
        } catch (_: Exception) {
            false
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
        cancelPressTick()
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
        popupLayer.detach() // re-attached on the next key press, which also covers a new IME window
        popupActive = false
        altUsesOverlay = false
        previewKey = null
        altShown = false
        altItems = emptyList()
        trailActive = false
        deleteSlideActive = false
        bgBitmap = null // drop the reference only; ShadeService owns the bitmap
    }

    fun release() {
        listener = null
        bgBitmap = null
        cancelPressTick()
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
        slidePath.clear()
        slidePointerId = -1
        touchOffsets.clear()
        popupLayer.detach()
        popupActive = false
        altUsesOverlay = false
        previewKey = null
        altShown = false
        altItems = emptyList()
        trailActive = false
        deleteSlideActive = false
    }

    /** Flush recorded touch offsets to the listener and clear for the next word. */
    fun flushTouchOffsets() {
        if (touchOffsets.isNotEmpty()) {
            listener?.onTouchOffsets(ArrayList(touchOffsets))
            touchOffsets.clear()
        }
    }

    /** Remove the last touch offset (backspace). */
    fun popTouchOffset() {
        if (touchOffsets.isNotEmpty()) touchOffsets.removeAt(touchOffsets.lastIndex)
    }

    /** Clear all touch offsets (new composing word). */
    fun clearTouchOffsets() {
        touchOffsets.clear()
    }
}
