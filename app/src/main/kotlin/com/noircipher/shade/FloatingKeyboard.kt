package com.noircipher.shade

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Wraps a keyboard view in a draggable, resizable WindowManager overlay.
 * Used when the user enables floating mode. Not used as an InputMethodService view.
 */
class FloatingKeyboard(context: Context) : FrameLayout(context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var params: WindowManager.LayoutParams? = null
    private var dragging = false
    private var resizing = false
    private var startX = 0f
    private var startY = 0f
    private var startW = 0
    private var startH = 0

    private val resizeHandle = View(context).apply {
        setBackgroundColor(0x44FFFFFF)
    }

    init {
        addView(resizeHandle, LayoutParams(48, 48).apply { gravity = Gravity.BOTTOM or Gravity.END })
        resizeHandle.setOnTouchListener { _, e -> handleResize(e) }
        setOnTouchListener { _, e -> handleDrag(e) }
    }

    fun show(content: View, width: Int, height: Int, x: Int, y: Int) {
        removeAllViews()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(resizeHandle, LayoutParams(48, 48).apply { gravity = Gravity.BOTTOM or Gravity.END })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try { wm.addView(this, params) } catch (_: Exception) {}
    }

    fun hide() {
        try { wm.removeView(this) } catch (_: Exception) {}
    }

    private fun handleDrag(e: MotionEvent): Boolean {
        val p = params ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                startX = e.rawX; startY = e.rawY
                startW = p.x; startH = p.y
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                p.x = startW + (e.rawX - startX).toInt()
                p.y = startH + (e.rawY - startY).toInt()
                try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun handleResize(e: MotionEvent): Boolean {
        val p = params ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = true
                startX = e.rawX; startY = e.rawY
                startW = p.width; startH = p.height
            }
            MotionEvent.ACTION_MOVE -> if (resizing) {
                p.width = (startW + (e.rawX - startX)).toInt().coerceAtLeast(320)
                p.height = (startH + (e.rawY - startY)).toInt().coerceAtLeast(200)
                try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resizing = false
        }
        return true
    }
}
