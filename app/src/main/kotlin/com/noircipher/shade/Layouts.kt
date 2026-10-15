package com.noircipher.shade

import android.graphics.Rect

object Codes {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val LETTERS = -5
    const val SYMBOLS2 = -6
    const val EMOJI = -7
    const val SPACE = 32
}

enum class KeyKind { CHAR, FUNC, SPACE, ENTER, SPACER }

class Key(
    val label: String,
    val code: Int = 0,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val alts: String = ""
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    val centerX: Float get() = (left + right) / 2f

    /** Pre-computed invalidate bounds; updated in KeyboardView.layoutKeys(). */
    var invalidateRect: Rect? = null

    fun updateInvalidateRect(pad: Int) {
        val r = invalidateRect ?: Rect().also { invalidateRect = it }
        r.set(
            (left - pad).toInt(),
            (top - pad).toInt(),
            (right + pad).toInt(),
            (bottom
