package com.noircipher.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.noircipher.shade.motion.ShadeMotion
import kotlin.math.max
import kotlin.math.min

/**
 * One popup layer for the whole keyboard: key-press previews AND long-press accent strips,
 * for every row.
 *
 * It is a [Drawable] added to the keyboard window's root [ViewGroup.getOverlay], which Android draws
 * after (on top of) every child view. So a popup is never hidden behind a key, the suggestion strip
 * or the toolbar, and it needs no separate window whose position could drift.
 *
 * Every popup looks and animates like the original bottom-row preview (scale 0.82 -> 1 with a fade,
 * a soft shadow, the theme's popup colour). Long-pressing morphs that bubble into an
 * accent panel: a rounded grid of cells with a round highlight on the selected one, while the rest
 * of the keyboard is dimmed by a scrim so the panel is the only thing in focus. Key previews never
 * dim anything; only the accent panel does.
 *
 * All coordinates passed in are in the owning view's (the keyboard's) coordinate space; the layer
 * converts them to window space itself.
 */
class KeyPopupOverlay(
    private val owner: View,
    private val theme: () -> KbTheme
) : Drawable() {

    private val density = owner.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var host: ViewGroup? = null
    // Owner's top-left expressed in host (window) coordinates.
    private var offX = 0f
    private var offY = 0f

    // ---- key preview ---------------------------------------------------------------------
    private var label = ""
    private var keyL = 0f
    private var keyT = 0f
    private var keyR = 0f
    private var rowH = 0f
    private var previewActive = false
    private var previewT = 0f
    private var previewAnim: ValueAnimator? = null

    // ---- accent panel -------------------------------------------------------------------
    // A grid of cells (see [AccentGrid]) inside one rounded panel. The panel is anchored above the
    // pressed key; the bubble -> panel morph starts from the last [showPreview] geometry
    // (keyL/keyT/keyR/rowH), so no extra key-position state is stored here.
    private var accActive = false
    private var accHiding = false
    private var hadBubble = false
    private var accT = 0f
    private var accAnim: ValueAnimator? = null
    private var accItems: List<String> = emptyList()
    private var accLayout: AccentGrid.Layout? = null
    private var accGeo: AccentGrid.Geometry? = null
    private var accSel = 0

    /**
     * The view whose bounds get dimmed while the accent panel is open (the whole keyboard: key
     * area, suggestion strip and toolbar). Null dims the owner view only.
     */
    var scrimAnchor: View? = null
    private val scrimRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val scrimPaint = Paint()
    private val rect = RectF()
    private val bubbleRect = RectF()
    private val gridRect = RectF()
    private val selRect = RectF()

    // ------------------------------------------------------------------ attach / geometry

    /**
     * Attaches the layer to the window's root view and refreshes the owner->window offset.
     * Returns false if that isn't possible right now (callers then fall back to in-view drawing).
     */
    fun prepare(): Boolean {
        if (!owner.isAttachedToWindow) return false
        val h = owner.rootView as? ViewGroup ?: return false
        if (h.width <= 0 || h.height <= 0) return false
        if (host !== h) {
            detach()
            try { h.overlay.add(this) } catch (_: Throwable) { return false }
            host = h
        }
        setBounds(0, 0, h.width, h.height)
        val a = IntArray(2)
        val b = IntArray(2)
        owner.getLocationInWindow(a)
        h.getLocationInWindow(b)
        offX = (a[0] - b[0]).toFloat()
        offY = (a[1] - b[1]).toFloat()
        return true
    }

    /**
     * Highest y (in owner coordinates) a popup may reach: the top of the window, which is above the
     * keyboard itself because the suggestion strip sits there. Can be negative.
     */
    fun minTopInOwner(): Float = -offY + dp(2f)

    fun detach() {
        dismissNow()
        host?.let { try { it.overlay.remove(this) } catch (_: Throwable) {} }
        host = null
    }

    // ------------------------------------------------------------------ key preview

    fun showPreview(text: String, left: Float, top: Float, right: Float, rowHeight: Float) {
        label = text
        keyL = left
        keyT = top
        keyR = right
        rowH = rowHeight
        previewActive = true
        previewAnim?.cancel()
        previewAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.KEY_POPUP_IN_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener { previewT = it.animatedValue as Float; invalidateSelf() }
            start()
        }
        invalidateSelf()
    }

    fun hidePreview() {
        if (!previewActive) return
        previewAnim?.cancel()
        previewAnim = ValueAnimator.ofFloat(previewT, 0f).apply {
            duration = ShadeMotion.KEY_POPUP_OUT_MS
            interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
            addUpdateListener { previewT = it.animatedValue as Float; invalidateSelf() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) { previewActive = false; invalidateSelf() }
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ accent strip

    /**
     * Show the accent panel.
     *
     * @param items  the characters, already display-shifted and deduplicated (at most [AccentGrid.MAX_ITEMS]).
     * @param layout which cell each item sits in.
     * @param geo    where the panel and its cells are, in owner coordinates.
     * @param sel    initially selected item (normally the one in the anchor cell).
     */
    fun showAccents(
        items: List<String>,
        layout: AccentGrid.Layout,
        geo: AccentGrid.Geometry,
        sel: Int
    ) {
        hadBubble = previewActive && previewT > 0.05f
        accItems = items
        accLayout = layout
        accGeo = geo
        accSel = sel.coerceIn(0, items.size - 1)
        accActive = true
        accHiding = false
        updateScrimRect()
        previewAnim?.cancel()
        previewActive = false // the bubble morphs into the panel
        accAnim?.cancel()
        accT = 0f
        accAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.KEY_POPUP_IN_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener { accT = it.animatedValue as Float; invalidateSelf() }
            start()
        }
        invalidateSelf()
    }

    /** Selects the item with index [sel]. */
    fun setAccentSelection(sel: Int) {
        if (!accActive || sel == accSel) return
        accSel = sel
        invalidateSelf()
    }

    private fun updateScrimRect() {
        val h = host
        val anchor = scrimAnchor
        if (h != null && anchor != null && anchor.isAttachedToWindow && anchor.width > 0) {
            val a = IntArray(2)
            val b = IntArray(2)
            anchor.getLocationInWindow(a)
            h.getLocationInWindow(b)
            val l = (a[0] - b[0]).toFloat()
            val t = (a[1] - b[1]).toFloat()
            scrimRect.set(l, t, l + anchor.width, t + anchor.height)
        } else {
            scrimRect.set(offX, offY, offX + owner.width, offY + owner.height)
        }
    }

    fun hideAccents() {
        if (!accActive) return
        accHiding = true
        accAnim?.cancel()
        accAnim = ValueAnimator.ofFloat(accT, 0f).apply {
            duration = ShadeMotion.KEY_POPUP_OUT_MS
            interpolator = ShadeMotion.FAST_OUT_LINEAR_IN
            addUpdateListener { accT = it.animatedValue as Float; invalidateSelf() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        accActive = false; accHiding = false
                        accItems = emptyList(); accLayout = null; accGeo = null
                        invalidateSelf()
                    }
                }
            })
            start()
        }
    }

    /** Removes everything immediately (view detached, keyboard hidden, input finished). */
    fun dismissNow() {
        previewAnim?.cancel()
        accAnim?.cancel()
        previewActive = false
        accActive = false
        accHiding = false
        accItems = emptyList()
        accLayout = null
        accGeo = null
        previewT = 0f
        accT = 0f
        invalidateSelf()
    }

    // ------------------------------------------------------------------ drawing

    override fun draw(canvas: Canvas) {
        // A grid fading out can overlap the next key's bubble, so both may be drawn.
        if (accActive && accT > 0.001f) drawAccents(canvas)
        if (previewActive && previewT > 0.001f) drawPreview(canvas)
    }

    /** Bubble geometry above the pressed key, in window coordinates (same shape as the bottom-row bubble). */
    private fun bubbleGeometry(scale: Float, out: RectF) {
        val w = (keyR - keyL) * 1.3f * scale
        val h = rowH * 1.25f * scale
        val hostW = bounds.width().toFloat()
        val lo = w / 2f + dp(2f)
        val hi = max(lo, hostW - w / 2f - dp(2f))
        val cx = (offX + (keyL + keyR) / 2f).coerceIn(lo, hi)
        var bottom = offY + keyT + dp(6f)
        var top = bottom - h
        if (top < dp(1f)) { // no room above the key: stay on screen
            bottom += dp(1f) - top
            top = dp(1f)
        }
        out.set(cx - w / 2f, top, cx + w / 2f, bottom)
    }

    private fun drawPreview(c: Canvas) {
        val th = theme()
        val t = previewT.coerceIn(0f, 1f)
        bubbleGeometry(0.82f + 0.18f * t, bubbleRect)
        val a = (255 * min(1f, t * 1.6f)).toInt()
        val r = dp(14f)

        rect.set(bubbleRect)
        rect.offset(0f, dp(2f))
        shadowPaint.color = ColorUtils.setAlphaComponent(0x000000, (0x40 * t).toInt())
        c.drawRoundRect(rect, r, r, shadowPaint)

        bgPaint.color = ColorUtils.setAlphaComponent(th.popupBg, a)
        c.drawRoundRect(bubbleRect, r, r, bgPaint)

        drawBubbleChar(c, bubbleRect, ColorUtils.setAlphaComponent(th.text, a))
    }

    private fun drawBubbleChar(c: Canvas, box: RectF, color: Int) {
        textPaint.color = color
        textPaint.textSize = min(box.height() * 0.5f, dp(32f))
        c.drawText(
            label, box.centerX(),
            box.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint
        )
    }

    private fun drawAccents(c: Canvas) {
        val th = theme()
        val geo = accGeo ?: return
        val layout = accLayout ?: return
        val t = accT.coerceIn(0f, 1f)

        // 1. Scrim: dims the whole keyboard so the panel is the only thing in focus. It fades with
        //    the panel and is never drawn for plain key previews.
        scrimPaint.color = ColorUtils.setAlphaComponent(
            0x000000, (scrimAlpha(th) * min(1f, t * 1.4f)).toInt()
        )
        c.drawRect(scrimRect, scrimPaint)

        // Final panel rect in window coordinates.
        gridRect.set(offX + geo.left, offY + geo.top, offX + geo.right, offY + geo.bottom)

        // The morph starts from the single-character bubble above the pressed key. If the press
        // showed no bubble (e.g. it was dismissed already) a small bubble is synthesised at the
        // panel's bottom centre so the panel still reads as "growing out of the key".
        bubbleGeometry(1f, bubbleRect)
        if (!hadBubble) {
            val s = 0.75f + 0.25f * t
            val w = gridRect.width() * s
            val h = gridRect.height() * s
            bubbleRect.set(
                gridRect.centerX() - w / 2f, gridRect.bottom - h,
                gridRect.centerX() + w / 2f, gridRect.bottom
            )
        }

        rect.set(
            lerp(bubbleRect.left, gridRect.left, t), lerp(bubbleRect.top, gridRect.top, t),
            lerp(bubbleRect.right, gridRect.right, t), lerp(bubbleRect.bottom, gridRect.bottom, t)
        )
        val a = when {
            accHiding -> min(1f, t * 2.2f)
            hadBubble -> 1f
            else -> min(1f, t * 1.6f)
        }
        val alpha = (255 * a).toInt()
        val r = lerp(dp(14f), dp(PANEL_RADIUS_DP), t)

        // 2. Shadow + panel background.
        selRect.set(rect)
        selRect.offset(0f, dp(3f))
        shadowPaint.color = ColorUtils.setAlphaComponent(0x000000, (0x55 * a).toInt())
        c.drawRoundRect(selRect, r, r, shadowPaint)
        bgPaint.color = ColorUtils.setAlphaComponent(th.popupBg, alpha)
        c.drawRoundRect(rect, r, r, bgPaint)

        // The just-typed base character fades out as the cells fade in.
        if (hadBubble && t < 0.99f) {
            val charAlpha = (alpha * (1f - t)).toInt().coerceIn(0, 255)
            drawBubbleChar(c, rect, ColorUtils.setAlphaComponent(th.text, charAlpha))
        }

        val cellsAlpha = if (hadBubble) (255 * t).toInt() else alpha
        if (cellsAlpha <= 0 || accItems.isEmpty()) return

        // 3. Round highlight on the selected cell, then the characters.
        val selCell = layout.cellOfItem.getOrNull(accSel) ?: return
        val hr = (geo.cell - dp(6f)) / 2f
        val sx = offX + geo.cellCenterX(layout.col(selCell))
        val sy = offY + geo.cellCenterY(layout.row(selCell))
        selPaint.color = ColorUtils.setAlphaComponent(th.text, cellsAlpha)
        c.drawCircle(sx, sy, hr, selPaint)

        itemPaint.textSize = min(geo.cell * 0.5f, dp(24f))
        val baseline = -(itemPaint.ascent() + itemPaint.descent()) / 2f
        for (i in accItems.indices) {
            val cell = layout.cellOfItem[i]
            itemPaint.color = ColorUtils.setAlphaComponent(
                if (i == accSel) th.popupBg else th.text, cellsAlpha
            )
            c.drawText(
                accItems[i],
                offX + geo.cellCenterX(layout.col(cell)),
                offY + geo.cellCenterY(layout.row(cell)) + baseline,
                itemPaint
            )
        }
    }

    /** Scrim strength: a little lighter on light themes so they do not turn muddy grey. */
    private fun scrimAlpha(th: KbTheme): Int =
        if (ColorUtils.calculateLuminance(th.bg) > 0.5) 0x59 else 0x8C

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ------------------------------------------------------------------ Drawable boilerplate

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Maximum number of cells shown in the accent panel. Longer [Key.alts] strings are truncated. */
        const val MAX_STRIP_ITEMS = AccentGrid.MAX_ITEMS
        private const val PANEL_RADIUS_DP = 24f
    }
}
