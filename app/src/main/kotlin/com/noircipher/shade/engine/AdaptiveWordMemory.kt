package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Recency-aware user word memory.
 *
 * Each accepted word has a stored [score] that grows with use and decays with
 * disuse. The score returned by [score] is the stored score multiplied by a
 * recency decay factor, so recently-used words surface in suggestions even
 * if their stored score hasn't grown much yet.
 *
 * Post-fix:
 *  - The boost in [record] is ALWAYS >= 1 — using a word never lowers its
 *    stored score. Previously, the boost could be 0.95 or 0.7 for stale
 *    entries, which paradoxically decreased the score on use. The decay is
 *    now applied only at read time, in [score].
 *  - The previous "double decay" (mutating in [record] AND multiplying in
 *    [score]) is gone.
 *  - [clear] is for user-initiated "Reset learned data". It is NOT called
 *    from [ShadeService.onDestroy] — that was a critical data-loss bug.
 *    [shutdown] is the right call for graceful shutdown.
 *  - [score] is normalized to [0, 1] so the learned boost doesn't dominate
 *    the ranker.
 */
class AdaptiveWordMemory(context: Context) {
    private data class Entry(var score: Float, var lastUsed: Long)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_adaptive_words", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val values = HashMap<String, Entry>()
    private var dirty = false
    private var flushJob: kotlinx.coroutines.Job? = null

    init { load() }

    private fun load() {
        try {
            val root = JSONObject(prefs.getString("words", "{}"))
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                values[key] = Entry(o.optDouble("s", 0.0).toFloat(), o.optLong("t", 0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load adaptive words; starting fresh", e)
        }
    }

    @Synchronized
    fun record(word: String) {
        val key = word.trim().lowercase()
        if (key.length !in 2..32) return
        val now = System.currentTimeMillis()
        val old = values[key]
        val oldScore = old?.score ?: 0f
        val last = old?.lastUsed ?: 0L
        val boost = when {
            last == 0L   -> 1.0f
            now - last < HOUR_MS   -> 1.5f
            now - last < DAY_MS    -> 1.1f
            now - last < MONTH_MS  -> 1.0f
            else                   -> 1.0f
        }
        val newScore = (oldScore * boost + 1f).coerceAtMost(MAX_SCORE)
        values[key] = Entry(newScore, now)
        if (values.size > MAX_ENTRIES) {
            values.entries.sortedBy { it.value.lastUsed }
                .take(values.size - MAX_ENTRIES)
                .forEach { values.remove(it.key) }
        }
        dirty = true
        scheduleFlush()
    }

    @Synchronized
    fun score(word: String): Float {
        val entry = values[word.trim().lowercase()] ?: return 0f
        val days = ((System.currentTimeMillis() - entry.lastUsed) / DAY_MS.toFloat()).coerceAtLeast(0f)
        val decay = when {
            days < 1f  -> 1.0f
            days < 7f  -> 0.9f
            days < 30f -> 0.6f
            else       -> 0.25f
        }
        return (entry.score * decay) / MAX_SCORE
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            flush()
        }
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        try {
            val root = JSONObject()
            values.forEach { (k, v) ->
                root.put(k, JSONObject().put("s", v.score.toDouble()).put("t", v.lastUsed))
            }
            prefs.edit().putString("words", root.toString()).apply()
            dirty = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush adaptive words", e)
        }
    }

    fun shutdown() {
        flushJob?.cancel()
        flush()
        scope.cancel()
    }

    @Synchronized
    fun clear() {
        scope.cancel()
        values.clear()
        prefs.edit().clear().apply()
        dirty = false
    }

    companion object {
        private const val TAG = "AdaptiveWordMemory"
        private const val MAX_SCORE = 80f
        private const val MAX_ENTRIES = 1200
        private const val FLUSH_DEBOUNCE_MS = 1200L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val MONTH_MS = 30L * DAY_MS
    }
}
