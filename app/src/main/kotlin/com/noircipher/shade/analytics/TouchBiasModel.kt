package com.noircipher.shade.analytics

import android.content.Context
import com.noircipher.shade.Key
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

    private data class Acc(var n: Int = 0, var sx: Double = 0.0, var sy: Double = 0.0, var sxx: Double = 0.0, var syy: Double = 0.0)

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
        val acc = values.getOrPut(label) { Acc() }
        acc.n += 1
        acc.sx += dx
        acc.sy += dy
        acc.sxx += dx * dx
        acc.syy += dy * dy
        dirty = true
        scheduleFlush()
    }

    @Synchronized
    fun bias(label: String): Bias? = toBias(values[label.lowercase()])

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
                )
            }
        } catch (_: Exception) {}
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
                .put("syy", acc.syy))
        }
        prefs.edit().putString("bias", root.toString()).apply()
        dirty = false
    }

    @Synchronized
    fun clear() {
        scope.cancel()
        prefs.edit().clear().apply()
        values.clear()
    }
}
