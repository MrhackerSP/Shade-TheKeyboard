package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Remembers autocorrects the user keeps reverting, so the same wrong
 * correction isn't applied again.
 *
 * Post-fix:
 *  - The (prev, typed, corrected) key uses a NUL separator. Previously it
 *    joined strings without a separator, so ("a","bc","d") and ("ab","c","d")
 *    collided and falsely suppressed each other.
 *  - Eviction is access-ordered LRU (LinkedHashMap(accessOrder=true)).
 *  - [clear] is exposed for the user-facing "Reset learned data" action.
 */
class AutocorrectSuppressor(context: Context) {
    private data class Entry(var count: Int, var lastSeen: Long)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_autocorrect_suppressor", Context.MODE_PRIVATE)
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val maxEntries = 256
    private val ttlMs = 30L * 24L * 60L * 60L * 1000L

    init { load() }

    @Synchronized
    fun shouldSuppress(prev: String?, typed: String, corrected: String): Boolean {
        val key = key(prev, typed, corrected)
        val now = System.currentTimeMillis()
        val entry = cache[key] ?: return false
        if (now - entry.lastSeen >= ttlMs) return false
        return entry.count >= SUPPRESS_THRESHOLD
    }

    @Synchronized
    fun recordRejection(prev: String?, typed: String, corrected: String) {
        val key = key(prev, typed, corrected)
        val now = System.currentTimeMillis()
        val old = cache[key]
        cache[key] = Entry((old?.count ?: 0) + 1, now)
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (now - e.lastSeen >= ttlMs) it.remove() else break
        }
        while (cache.size > maxEntries) {
            val first = cache.entries.iterator().next()
            cache.remove(first.key)
        }
        save()
    }

    private fun key(prev: String?, typed: String, corrected: String): String =
        prev.orEmpty().lowercase() + "\u0000" +
        typed.lowercase() + "\u0000" +
        corrected.lowercase()

    private fun save() {
        try {
            val root = JSONObject()
            cache.forEach { (k, v) -> root.put(k, JSONObject().put("c", v.count).put("t", v.lastSeen)) }
            prefs.edit().putString("items", root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save autocorrect suppressor", e)
        }
    }

    private fun load() {
        try {
            val root = JSONObject(prefs.getString("items", "{}"))
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                cache[k] = Entry(o.optInt("c", 0), o.optLong("t", 0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load autocorrect suppressor; starting fresh", e)
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "AutocorrectSuppressor"
        private const val SUPPRESS_THRESHOLD = 2
    }
}
