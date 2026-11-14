package com.noircipher.shade.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.noircipher.shade.UserWordCodec
import org.json.JSONObject

/**
 * Personal correction memory: remembers which fix the user kept for a word
 * they typed before, and pre-approves that fix next time.
 *
 * This is the local, privacy-safe analogue of a personal typo model: when the
 * user accepts a correction ("teh" -> "the") we store the pair; when they
 * REVERT the correction (backspace) we unlearn it. After [APPROVE_COUNT]
 * consistent accepts, the same correction is applied without re-passing the
 * confidence gate — the user has already voted for it with their fingers.
 *
 * Storage matches Shade's other adaptive stores: a bounded JSON blob in
 * SharedPreferences (same pattern as AutocorrectSuppressor / AdaptiveWordMemory),
 * on-device only, cleared by "Clear learned data".
 */
class CorrectionMemory(context: Context) {

    data class Entry(val corrected: String, val count: Int, val lastSeen: Long)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var loaded = false

    init { load() }

    /** The correction was accepted (committed without revert). */
    @Synchronized
    fun record(original: String, corrected: String) {
        val key = norm(original)
        val fix = norm(corrected)
        if (!validPair(key, fix)) return
        ensureLoad()
        val now = System.currentTimeMillis()
        val old = cache[key]
        val entry = if (old != null && old.corrected == fix) {
            Entry(fix, (old.count + 1).coerceAtMost(MAX_COUNT), now)
        } else {
            // Conflicting fix (user changed their mind): restart the count at 1.
            Entry(fix, 1, now)
        }
        cache[key] = entry
        evict(now)
        save()
    }

    /** The correction was reverted — the user rejected the pair. */
    @Synchronized
    fun unlearn(original: String, corrected: String) {
        val key = norm(original)
        val fix = norm(corrected)
        if (key.isEmpty()) return
        ensureLoad()
        val old = cache[key] ?: return
        if (old.corrected != fix) return
        val remaining = old.count - REVERT_PENALTY
        if (remaining <= 0) {
            cache.remove(key)
        } else {
            cache[key] = Entry(old.corrected, remaining, old.lastSeen)
        }
        save()
    }

    /** What the user usually means by [original], or null. Only pairs seen [APPROVE_COUNT]+ times. */
    @Synchronized
    fun recall(original: String): Entry? {
        val key = norm(original)
        if (key.isEmpty()) return null
        ensureLoad()
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.lastSeen >= TTL_MS) return null
        return if (entry.count >= APPROVE_COUNT) entry else null
    }

    /** True when this exact pair has been accepted enough times to pre-approve. */
    fun approves(original: String, corrected: String): Boolean {
        val entry = recall(original) ?: return false
        return entry.corrected == norm(corrected)
    }

    @Synchronized
    fun size(): Int {
        ensureLoad()
        return cache.size
    }

    @Synchronized
    fun clear() {
        cache.clear()
        loaded = true
        prefs.edit().clear().apply()
    }

    /** Re-reads from disk after a backup restore replaced the underlying store. */
    @Synchronized
    fun reload() {
        cache.clear()
        loaded = false
        ensureLoad()
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun norm(s: String): String = s.trim().lowercase()

    private fun validPair(original: String, corrected: String): Boolean {
        if (original == corrected) return false
        if (original.length !in MIN_LEN..MAX_LEN) return false
        if (corrected.length !in MIN_LEN..MAX_LEN + 16) return false
        if (!original.all { UserWordCodec.isWordChar(it) }) return false
        if (!corrected.all { UserWordCodec.isWordChar(it) || it == ' ' }) return false
        return true
    }

    private fun ensureLoad() {
        if (loaded) return
        load()
    }

    private fun load() {
        loaded = true
        try {
            val root = JSONObject(prefs.getString("pairs", "{}"))
            val now = System.currentTimeMillis()
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val fixed = o.optString("c", "")
                val count = o.optInt("n", 0)
                val seen = o.optLong("t", 0L)
                if (fixed.isEmpty() || count <= 0 || now - seen >= TTL_MS) continue
                cache[k] = Entry(fixed, count, seen)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load correction memory; starting fresh", e)
            cache.clear()
        }
    }

    /** LRU + TTL eviction, bounded like AutocorrectSuppressor. */
    private fun evict(now: Long) {
        cache.entries.removeAll { now - it.value.lastSeen >= TTL_MS }
        while (cache.size > MAX_ENTRIES) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
        }
    }

    private fun save() {
        try {
            val root = JSONObject()
            for ((k, v) in cache) {
                root.put(k, JSONObject().put("c", v.corrected).put("n", v.count).put("t", v.lastSeen))
            }
            prefs.edit().putString("pairs", root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save correction memory", e)
        }
    }

    companion object {
        private const val TAG = "CorrectionMemory"
        private const val PREFS_FILE = "shade_correction_memory"
        private const val MIN_LEN = 2
        private const val MAX_LEN = 32
        private const val MAX_ENTRIES = 300
        private const val MAX_COUNT = 50
        private const val TTL_MS = 90L * 24L * 60L * 60L * 1000L  // 90 days
        private const val REVERT_PENALTY = 2

        /** Accepts needed before a pair pre-approves future corrections. */
        const val APPROVE_COUNT = 2
    }
}
