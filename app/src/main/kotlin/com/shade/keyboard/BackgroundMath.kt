package com.shade.keyboard

/**
 * Background-image math for the keyboard wallpaper, expressed over plain
 * ints/arrays so it runs — and is unit-tested — on the plain JVM.
 * [decodeSampleSize] mirrors the render-time decode cap (2048 px);
 * [frame] implements the photo draw math: COVER = max(scaleX, scaleY),
 * FIT = min, STRETCH = axis-independent 1:1, then zoom / width / height
 * factors and the centered pan offsets (pan is clamped so COVER can never
 * reveal empty strips at the edges);
 * [keyAlpha] / [dimAlpha] / [bgArgb] map the settings sliders.
 */
object BackgroundMath {
    enum class Fit { COVER, FIT, STRETCH }

    fun decodeSampleSize(srcW: Int, srcH: Int, cap: Int = 2048): Int {
        var sample = 1
        val longest = maxOf(srcW, srcH)
        if (longest <= 0) return 1
        while (longest / sample > cap) sample *= 2
        return sample
    }

    /**
     * Destination rectangle for the background image inside the keyboard
     * area: (left, top, width, height). Zoom / width / height factors apply
     * to every fit; STRETCH simply uses a 1:1 base scale.
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
        // Clamp pan so an overflowing (COVER) image can never show empty strips.
        val maxX = maxOf(0f, (width - viewW) / viewW)
        val maxY = maxOf(0f, (height - viewH) / viewH)
        val cx = panX.coerceIn(-maxX, maxX)
        val cy = panY.coerceIn(-maxY, maxY)
        val left = (viewW - width + viewW * cx) / 2f
        val top = (viewH - height + viewH * cy) / 2f
        return floatArrayOf(left, top, width, height)
    }

    fun dimAlpha(dimPct: Int): Int = (255 * dimPct.coerceIn(0, 90)) / 100

    fun keyAlpha(opacityPct: Int): Int = (255 * opacityPct.coerceIn(20, 100)) / 100

    fun bgArgb(bg: Int, transparencyPct: Int): Int {
        val alpha = 255 - (255 * transparencyPct.coerceIn(0, 100)) / 100
        return (bg and 0x00FFFFFF) or (alpha shl 24)
    }

    /**
     * Separable box blur over packed ARGB pixels (row-major). Two edge-clamped
     * sliding window passes (horizontal + vertical). Radius 0 returns a copy.
     */
    fun boxBlurPx(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        require(w > 0 && h > 0) { "degenerate dimensions" }
        require(src.size == w * h) { "pixel buffer size mismatch" }
        if (radius <= 0) return src.copyOf()
        val r = radius.coerceAtMost(minOf(w, h))
        val window = r * 2 + 1
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
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
