package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Suppresses suggestions the user keeps rejecting.
 *
 * When a candidate word is *shown* to the user (in the suggestion bar) but
 * not picked, [recordRejection] bumps its rejection count.  Once a word's
 * count crosses [REJECT_THRESHOLD], [filter] removes it from future
 * suggestion lists — until either the user types it (which resets the count
 * via [recordAcceptance]) or the TTL expires.
 *
 * Thread-safe; persists counts to a SharedPreferences file so they survive
 * process restarts.
 */
class SuggestionRejectionTracker(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_suggestion_rejection", Context.MODE_PRIVATE)
    private val counts = ConcurrentHashMap<String, Int>()
    private val timestamps = ConcurrentHashMap<String, Long>()

    init { load() }

    @Synchronized
    fun recordRejection(word: String, now: Long = System.currentTimeMillis()) {
        val w = word.lowercase().trim()
        if (w.isEmpty()) return
        val cur = counts[w] ?: 0
        counts[w] = cur + 1
        timestamps[w] = now
        save()
    }

    @Synchronized
    fun recordAcceptance(word: String) {
        val w = word.lowercase().trim()
        if (w.isEmpty()) return
        // Accepting a word clears its rejection history.
        counts.remove(w)
        timestamps.remove(w)
        save()
    }

    /**
     * Removes from [candidates] any word whose rejection count is currently
     * above [REJECT_THRESHOLD] and whose most recent rejection is within
     * [TTL_MS].  Returns the filtered list.
     */
    @Synchronized
    fun filter(candidates: List<String>, now: Long = System.currentTimeMillis()): List<String> {
        if (candidates.isEmpty()) return candidates
        return candidates.filter { c ->
            val w = c.lowercase().trim()
            val count = counts[w] ?: 0
            if (count < REJECT_THRESHOLD) true
            else {
                val ts = timestamps[w] ?: 0L
                (now - ts) >= TTL_MS
            }
        }
    }

    @Synchronized
    fun rejectionCount(word: String): Int = counts[word.lowercase().trim()] ?: 0

    @Synchronized
    fun clear() {
        counts.clear()
        timestamps.clear()
        prefs.edit().clear().apply()
    }

    private fun save() {
        try {
            val root = JSONObject()
            counts.forEach { (k, v) -> root.put(k, v) }
            prefs.edit().putString("counts", root.toString()).apply()
            val tsRoot = JSONObject()
            timestamps.forEach { (k, v) -> tsRoot.put(k, v) }
            prefs.edit().putString("timestamps", tsRoot.toString()).apply()
        } catch (_: Exception) {
            // Persistence is best-effort.
        }
    }

    private fun load() {
        try {
            val countsJson = prefs.getString("counts", "{}") ?: "{}"
            val tsJson = prefs.getString("timestamps", "{}") ?: "{}"
            val cRoot = JSONObject(countsJson)
            for (k in cRoot.keys()) counts[k] = cRoot.getInt(k)
            val tRoot = JSONObject(tsJson)
            for (k in tRoot.keys()) timestamps[k] = tRoot.getLong(k)
        } catch (_: Exception) {
            // Start fresh.
        }
    }

    companion object {
        private const val REJECT_THRESHOLD = 3
        private const val TTL_MS = 24L * 60L * 60L * 1000L  // 24 hours
    }
}
