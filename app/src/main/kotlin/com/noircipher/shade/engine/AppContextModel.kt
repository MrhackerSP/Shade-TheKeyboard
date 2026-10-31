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
import java.security.MessageDigest

/**
 * Per-app (SHA-256-hashed) vocabulary bias.
 *
 * Post-fix:
 *  - [currentHash] is now @Volatile. Previously [setCurrentPackage] wrote it
 *    from Main while [recordWord] / [score] read it on Dispatchers.Default
 *    under @Synchronized — a torn-read hazard.
 *  - The number of tracked apps is now capped at [MAX_APPS].
 *  - [shutdown] / [flush] are exposed for graceful service teardown.
 *  - [score] is normalized to [0, 1].
 */
class AppContextModel(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shade_app_context", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apps = HashMap<String, HashMap<String, Float>>()

    @Volatile
    private var currentHash: String = ""

    private val dirtyLock = Any()
    @Volatile private var dirty = false
    private var flushJob: kotlinx.coroutines.Job? = null

    private val sha256: MessageDigest by lazy { MessageDigest.getInstance("SHA-256") }

    init { load() }

    fun setCurrentPackage(packageName: String?) {
        currentHash = packageName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sha256.digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b) }
        }.orEmpty()
    }

    /** Returns the SHA-256 hash of the currently-active package, or "" if none. */
    fun currentPackageHash(): String = currentHash

    @Synchronized
    fun recordWord(word: String) {
        if (currentHash.isEmpty()) return
        val key = word.trim().lowercase()
        if (key.length !in 2..32) return
        val app = apps.getOrPut(currentHash) { HashMap() }
        app[key] = ((app[key] ?: 0f) + 1f).coerceAtMost(MAX_WORD_SCORE)
        if (app.size > MAX_WORDS_PER_APP) {
            app.entries.sortedBy { it.value }
                .take(app.size - MAX_WORDS_PER_APP)
                .forEach { app.remove(it.key) }
        }
        if (apps.size > MAX_APPS) {
            apps.entries.sortedBy { a -> a.value.values.sum() }
                .take(apps.size - MAX_APPS)
                .forEach { apps.remove(it.key) }
        }
        synchronized(dirtyLock) { dirty = true }
        scheduleFlush()
    }

    @Synchronized
    fun score(word: String): Float {
        if (currentHash.isEmpty()) return 0f
        return (apps[currentHash]?.get(word.trim().lowercase()) ?: 0f) / MAX_WORD_SCORE
    }

    /**
     * Returns the top-N highest-scoring words for the current package hash.
     * Used by [com.noircipher.shade.engine.PerAppVocabularySnapshot] to pre-load
     * an in-memory cache of app-specific vocabulary for fast prefix lookup.
     */
    @Synchronized
    fun topWords(limit: Int): List<String> {
        if (currentHash.isEmpty()) return emptyList()
        val app = apps[currentHash] ?: return emptyList()
        return app.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
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
        synchronized(dirtyLock) { if (!dirty) return; dirty = false }
        try {
            val root = JSONObject()
            apps.forEach { (app, words) ->
                val o = JSONObject()
                words.forEach { (w, s) -> o.put(w, s.toDouble()) }
                root.put(app, o)
            }
            prefs.edit().putString("apps", root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush app context model", e)
        }
    }

    fun shutdown() {
        flushJob?.cancel()
        flush()
        scope.cancel()
    }

    private fun load() {
        try {
            val root = JSONObject(prefs.getString("apps", "{}"))
            for (app in root.keys()) {
                val o = root.getJSONObject(app)
                val map = HashMap<String, Float>()
                for (w in o.keys()) map[w] = o.optDouble(w, 0.0).toFloat()
                apps[app] = map
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load app context model; starting fresh", e)
        }
    }

    @Synchronized
    fun clear() {
        scope.cancel()
        apps.clear()
        prefs.edit().clear().apply()
        synchronized(dirtyLock) { dirty = false }
    }

    companion object {
        private const val TAG = "AppContextModel"
        private const val MAX_APPS = 50
        private const val MAX_WORDS_PER_APP = 800
        private const val MAX_WORD_SCORE = 100f
        private const val FLUSH_DEBOUNCE_MS = 1500L
    }
}
