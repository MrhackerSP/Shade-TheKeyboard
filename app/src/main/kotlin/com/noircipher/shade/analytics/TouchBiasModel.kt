package com.noircipher.shade.analytics

import android.content.Context
import android.util.Log
import com.noircipher.shade.Key
import com.noircipher.shade.TouchGeometry
import com.noircipher.shade.KeyKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.sqrt

class TouchBiasModel(context: Context) {
    data class Bias(
        val count: Int,
        val avgDx: Float,
        val avgDy: Float,
        val stdDx: Float,
        val stdDy: Float
    )

    /**
     * Per-key landing offset in KEY UNITS (dx / key-width, dy / key-height).
     * This is the personalized touch model: subtracting [avgDx]/[avgDy] from a
     * fresh touch re-centers it on this user's habitual aim before the spatial
     * autocorrect scores it, so a user who consistently lands slightly
     * right-of-center is not misread as aiming for the next key.
     */
    data class NormBias(
        val count: Int,
        val avgDx: Float,
        val avgDy: Float
    )

    private data class Acc(var n: Int = 0, var sx: Double = 0.0, var sy: Double = 0.0, var sxx: Double = 0.0, var syy: Double = 0.0) {
        // Normalized accumulators (key units). Kept separate so the pixel-based
        // heatmap display and its stored data keep working unchanged.
        var snx: Double = 0.0
        var sny: Double = 0.0
    }

    private val prefs = context.getSharedPreferences("shade_touch_bias", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val values = HashMap<String, Acc>()
    private var dirty = false
    private var flushJob: kotlinx.coroutines.Job? = null

    init { load() }

    @Synchronized
    fun record(key: Key, x: Float, y: Float) {
        if (key.kind != KeyKind.CHAR) return
        val label = key.label.lowercase()
        if (label.length != 1 || !label[0].isLetter()) return
        val cx = (key.left + key.right) / 2f
        val cy = (key.top + key.bottom) / 2f
        val dx = x - cx
        val dy = y - cy
        val w = (key.right - key.left).coerceAtLeast(1f)
        val h = (key.bottom - key.top).coerceAtLeast(1f)
        val acc = values.getOrPut(label) { Acc() }
        acc.n += 1
        acc.sx += dx
        acc.sy += dy
        acc.sxx += dx * dx
        acc.syy += dy * dy
        acc.snx += dx / w
        acc.sny += dy / h
        dirty = true
        scheduleFlush()
    }

    @Synchronized
    fun bias(label: String): Bias? = toBias(values[label.lowercase()])

    /** Normalized per-key bias (key units) once the key has enough samples. */
    @Synchronized
    fun normalizedBias(label: String): NormBias? {
        val acc = values[label.lowercase()] ?: return null
        if (acc.n < MIN_REMAP_SAMPLES) return null
        val mx = (acc.snx / acc.n).toFloat()
        val my = (acc.sny / acc.n).toFloat()
        // Sanity clamp: a "habitual offset" larger than a third of the key is
        // not a bias, it is noise (or a layout change) — ignore it.
        if (kotlin.math.abs(mx) > MAX_BIAS_UNITS || kotlin.math.abs(my) > MAX_BIAS_UNITS) return null
        return NormBias(acc.n, mx, my)
    }

    /**
     * Re-centers one touch for [typedChar] by the user's learned bias.
     * Returns the touch unchanged when no reliable bias exists.
     */
    fun remap(typedChar: Char, touch: TouchGeometry.KeyTouch): TouchGeometry.KeyTouch {
        val b = normalizedBias(typedChar.lowercaseChar().toString()) ?: return touch
        val dx = (touch.dx - b.avgDx).coerceIn(-0.75f, 0.75f)
        val dy = (touch.dy - b.avgDy).coerceIn(-0.75f, 0.75f)
        return TouchGeometry.KeyTouch(dx, dy)
    }

    /** Remaps a whole word's touch list (touch i belongs to [word]'s char i). */
    fun remapAll(word: String, touches: List<TouchGeometry.KeyTouch>): List<TouchGeometry.KeyTouch> {
        if (touches.isEmpty()) return touches
        val out = ArrayList<TouchGeometry.KeyTouch>(touches.size)
        val n = minOf(word.length, touches.size)
        for (i in touches.indices) {
            out.add(if (i < n) remap(word[i], touches[i]) else touches[i])
        }
        return out
    }

    @Synchronized
    fun all(): Map<String, Bias> = values.mapNotNull { (k, v) -> toBias(v)?.let { k to it } }.toMap()

    private fun toBias(acc: Acc?): Bias? {
        if (acc == null || acc.n < 3) return null
        val mx = (acc.sx / acc.n).toFloat()
        val my = (acc.sy / acc.n).toFloat()
        val vx = ((acc.sxx / acc.n) - mx * mx).toFloat().coerceAtLeast(0f)
        val vy = ((acc.syy / acc.n) - my * my).toFloat().coerceAtLeast(0f)
        return Bias(acc.n, mx, my, sqrt(vx), sqrt(vy))
    }

    private fun load() {
        try {
            val root = JSONObject(prefs.getString("bias", "{}"))
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                values[key] = Acc(
                    n = o.optInt("n", 0),
                    sx = o.optDouble("sx", 0.0),
                    sy = o.optDouble("sy", 0.0),
                    sxx = o.optDouble("sxx", 0.0),
                    syy = o.optDouble("syy", 0.0)
                ).also { it.snx = o.optDouble("snx", 0.0); it.sny = o.optDouble("sny", 0.0) }
            }
        } catch (e: Exception) { Log.w("TouchBiasModel", "Failed to parse touch bias model", e) }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(1000)
            flush()
        }
    }

    @Synchronized
    private fun flush() {
        if (!dirty) return
        val root = JSONObject()
        for ((key, acc) in values) {
            if (acc.n == 0) continue
            root.put(key, JSONObject()
                .put("n", acc.n)
                .put("sx", acc.sx)
                .put("sy", acc.sy)
                .put("sxx", acc.sxx)
                .put("syy", acc.syy)
                .put("snx", acc.snx)
                .put("sny", acc.sny))
        }
        prefs.edit().putString("bias", root.toString()).apply()
        dirty = false
    }

    @Synchronized
    fun clear() {
        flushJob?.cancel()
        prefs.edit().clear().apply()
        values.clear()
    }

    /** Re-reads from disk after a backup restore replaced the underlying file. */
    @Synchronized
    fun reload() {
        flush()
        flushJob?.cancel()
        values.clear()
        load()
        dirty = false
    }

    companion object {
        /** Remap needs a stable per-key sample count before it is trusted. */
        const val MIN_REMAP_SAMPLES = 5
        /** Largest believable habitual offset, in key units. */
        const val MAX_BIAS_UNITS = 0.25f
    }
}
