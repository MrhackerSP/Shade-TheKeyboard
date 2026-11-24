package com.shade.keyboard

/**
 * Background-image math for the keyboard wallpaper, adapted to Shade's View
 * renderer and expressed over plain ints/arrays so it runs — and is
 * unit-tested — on the plain JVM.
 *
 * Parity notes:
 *  - [decodeSampleSize] mirrors the render-time decode guard
 *    `while (maxOf(w, h) / sample > 2048) sample *= 2`;
 *  - [frame] implements the photo draw math: COVER = max(scaleX, scaleY),
 *    FIT = min, STRETCH = axis-independent 1:1, then zoom / width / height
 *    factors and the centered ±1 pan offsets;
 *  - [keyAlpha] / [dimAlpha] / [bgArgb] map the `key_opacity`
 *    (coerced 0.2..1), `bg_dim` (0..0.9) and `bg_transparency` (0..1) sliders
 *    as integer percentages, which is what Shade's settings sliders store.
 */
object BackgroundMath {

    /** Photo placement options. */
    enum class Fit { COVER, FIT, STRETCH }

    // ── Decode sampling ───────────────────────────────────────────────────

    /**
     * Power-of-two inSampleSize so the longest decoded side fits [cap]
     * (the 2048 px render cap). Returns 1 for already-small images.
     */
    fun decodeSampleSize(srcW: Int, srcH: Int, cap: Int = 2048): Int {
        var sample = 1
        val longest = maxOf(srcW, srcH)
        if (longest <= 0) return 1
        while (longest / sample > cap) sample *= 2
        return sample
    }

    // ── Placement ─────────────────────────────────────────────────────────

    /**
     * Destination rectangle for the background image inside the keyboard
     * area: (left, top, width, height). Implements the draw math including
     * the quirk that STRETCH ignores zoom/width/height but still honors the
     * pan offsets.
     *
     * @param zoom      1.0 = natural (50..300% slider)
     * @param widthF    frame width factor (50..200% slider)
     * @param heightF   frame height factor (50..200% slider)
     * @param panX      horizontal pan, -1..1 (slider -100..100)
     * @param panY      vertical pan, -1..1
     */
    fun frame(
        fit: Fit,
        imgW: Int,
        imgH: Int,
        viewW: Int,
        viewH: Int,
        zoom: Float = 1f,
        widthF: Float = 1f,
        heightF: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f
    ): FloatArray {
        require(imgW > 0 && imgH > 0 && viewW > 0 && viewH > 0) { "degenerate dimensions" }
        val scaleX = viewW.toFloat() / imgW
        val scaleY = viewH.toFloat() / imgH
        val scale = when (fit) {
            Fit.COVER -> maxOf(scaleX, scaleY)
            Fit.FIT -> minOf(scaleX, scaleY)
            Fit.STRETCH -> 1f
        }
        val width = (if (fit == Fit.STRETCH) viewW.toFloat() else imgW * scale) * zoom * widthF
        val height = (if (fit == Fit.STRETCH) viewH.toFloat() else imgH * scale) * zoom * heightF
        val left = (viewW - width + viewW * panX) / 2f
        val top = (viewH - height + viewH * panY) / 2f
        return floatArrayOf(left, top, width, height)
    }

    // ── Alpha composition ─────────────────────────────────────────────────

    /** `bg_dim` 0..90 (%) → the dim overlay's 0..255 alpha channel. */
    fun dimAlpha(dimPct: Int): Int = (255 * dimPct.coerceIn(0, 90)) / 100

    /**
     * `key_opacity` 20..100 (%) → the alpha multiplier applied to key fills.
     * 100 → 255 (opaque, no change from today's rendering).
     */
    fun keyAlpha(opacityPct: Int): Int = (255 * opacityPct.coerceIn(20, 100)) / 100

    /**
     * `bg_transparency` 0..100 (%) → the keyboard background color with its
     * alpha channel reduced so the app behind the IME shows through.
     */
    fun bgArgb(bg: Int, transparencyPct: Int): Int {
        val alpha = 255 - (255 * transparencyPct.coerceIn(0, 100)) / 100
        return (bg and 0x00FFFFFF) or (alpha shl 24)
    }

    // ── Blur ──────────────────────────────────────────────────────────────

    /**
     * Separable box blur over packed ARGB pixels (row-major, opaque images
     * assumed but every channel is processed). Two edge-clamped sliding
     * window passes (horizontal + vertical). Radius 0 returns a copy.
     *
     * Shade's renderer has no hardware blur (GPU RenderEffect blur is an
     * API 31+ platform feature), so the blur is baked into the
     * decoded bitmap once per version bump instead — same visual intent at a
     * one-time CPU cost.
     */
    fun boxBlurPx(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        require(w > 0 && h > 0) { "degenerate dimensions" }
        require(src.size == w * h) { "pixel buffer size mismatch" }
        if (radius <= 0) return src.copyOf()
        val r = radius.coerceAtMost(minOf(w, h))
        val window = r * 2 + 1
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)

        // Horizontal pass: src → tmp.
        for (y in 0 until h) {
            val row = y * w
            for (ch in 0 until 4) {
                val shift = ch * 8
                var sum = 0
                for (i in -r..r) {
                    val x = i.coerceIn(0, w - 1)
                    sum += (src[row + x] shr shift) and 0xFF
                }
                for (x in 0 until w) {
                    tmp[row + x] = tmp[row + x] or ((sum / window) shl shift)
                    val dropped = (src[row + (x - r).coerceIn(0, w - 1)] shr shift) and 0xFF
                    val added = (src[row + (x + r + 1).coerceIn(0, w - 1)] shr shift) and 0xFF
                    sum += added - dropped
                }
            }
        }

        // Vertical pass: tmp → out.
        for (x in 0 until w) {
            for (ch in 0 until 4) {
                val shift = ch * 8
                var sum = 0
                for (i in -r..r) {
                    val y = i.coerceIn(0, h - 1)
                    sum += (tmp[y * w + x] shr shift) and 0xFF
                }
                for (y in 0 until h) {
                    out[y * w + x] = out[y * w + x] or ((sum / window) shl shift)
                    val dropped = (tmp[(y - r).coerceIn(0, h - 1) * w + x] shr shift) and 0xFF
                    val added = (tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] shr shift) and 0xFF
                    sum += added - dropped
                }
            }
        }
        return out
    }
}
