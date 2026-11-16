package com.shade.keyboard.engine

import android.content.Context
import com.shade.keyboard.DebouncedSaver
import com.shade.keyboard.EncryptedMapFile
import com.shade.keyboard.SecureStore
import com.shade.keyboard.UserWordCodec
import com.shade.keyboard.WordDictionary

/**
 * Per-word usage counters (bump on use, penalize on rejection).
 *
 * Persisted as a single Keystore-encrypted file (no plaintext words at rest).
 * State is shared process-wide, so [clear] (e.g. "Clear learned data" in
 * settings) also resets the instance held by the running keyboard service.
 */
class UserWordMemory(context: Context) {

    private val app = context.applicationContext

    init { State.ensureLoaded(app) }

    fun bump(word: String) = State.change(word) { (it + 1).coerceAtMost(200) }

    fun penalize(word: String) = State.change(word) { (it - 3).coerceAtLeast(-50) }

    /** Removes the word entirely from the learned memory ("Remove suggestion"). */
    fun forget(word: String) = State.forget(word)

    /**
     * Bulk import of learned words (migration from another keyboard / word lists).
     * Words already known are left untouched and reported as duplicates.
     * Returns (imported, duplicates).
     */
    fun importWords(words: Map<String, Int>): Pair<Int, Int> = State.importWords(words)

    fun score(word: String): Float {
        val value = State.get(word)
        if (value <= 0) return 0f
        return (value / 25f).coerceIn(0f, 1f)
    }

    /**
     * Snapshot of all user words with positive scores.
     * Returned map: word (lowercased) -> score in [0, 1].
     */
    fun snapshot(): Map<String, Float> = State.snapshot()

    fun clear() = State.clear(app)

    /** Re-reads from disk after a backup restore replaced the encrypted store. */
    fun reload() = State.reload(app)

    private object State {
        private const val FILE = "user_word_memory.enc"
        private const val LEGACY_PREFS = "shade_user_word_memory"
        private const val MAX_ENTRIES = 5000

        private val lock = Any()
        private var counts: HashMap<String, Int>? = null
        private var store: EncryptedMapFile? = null
        private var dirty = false
        private var version = 0L
        private var saver: DebouncedSaver? = null

        private fun norm(word: String) = word.trim().lowercase()

        fun ensureLoaded(ctx: Context) = synchronized(lock) {
            if (counts != null) return@synchronized
            val s = EncryptedMapFile(ctx, FILE, WordDictionary.USER_KEY_ALIAS)
            store = s
            // One saver for the process lifetime: creating a new DebouncedSaver
            // here (e.g. after backup restore) would leak its executor thread.
            if (saver == null) saver = DebouncedSaver(2000L) { flush() }
            val loaded: Map<String, Float> = s.load() ?: migrateLegacy(ctx, s)
            counts = HashMap<String, Int>().also { m ->
                for ((w, v) in loaded) m[w] = v.toInt()
            }
        }

        /** One-time import of the old plaintext "w:<word>" SharedPreferences store. */
        private fun migrateLegacy(ctx: Context, s: EncryptedMapFile): Map<String, Float> {
            val prefs = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val all = prefs.all
            if (all.isEmpty()) return emptyMap()
            val migrated = UserWordCodec.fromLegacyEntries(all, positiveOnly = false) { k ->
                if (k.startsWith("w:")) k.substring(2) else k
            }
            // Never leave plaintext words behind: drop the legacy file once the
            // encrypted copy is on disk (or if there was nothing worth keeping).
            if (migrated.isEmpty() || s.save(migrated)) prefs.edit().clear().apply()
            return migrated
        }

        fun get(word: String): Int = synchronized(lock) { counts?.get(norm(word)) ?: 0 }

        fun change(word: String, f: (Int) -> Int) {
            val w = norm(word)
            if (!UserWordCodec.isValidWord(w)) return
            synchronized(lock) {
                val m = counts ?: return
                m[w] = f(m[w] ?: 0)
                if (m.size > MAX_ENTRIES) {
                    val drop = m.entries.sortedBy { it.value }.take(m.size / 10).map { it.key }
                    for (k in drop) m.remove(k)
                }
                dirty = true
                version++
            }
            saver?.request()
        }

        fun forget(word: String) {
            val w = norm(word)
            if (w.isEmpty()) return
            synchronized(lock) {
                if (counts?.remove(w) != null) {
                    dirty = true
                    version++
                }
            }
            saver?.request()
        }

        /**
         * Seeds counters for imported words. Each word enters at a moderate count
         * (capped so an imported dictionary never out-weights genuinely typed
         * words); duplicates are skipped and reported.
         */
        fun importWords(words: Map<String, Int>): Pair<Int, Int> {
            var imported = 0
            var duplicates = 0
            synchronized(lock) {
                val m = counts ?: return 0 to 0
                for ((raw, hint) in words) {
                    val w = norm(raw)
                    if (!UserWordCodec.isValidWord(w)) continue
                    if (m.containsKey(w)) {
                        duplicates++
                        continue
                    }
                    m[w] = hint.coerceIn(1, 12)
                    imported++
                }
                if (imported > 0) {
                    dirty = true
                    version++
                }
            }
            if (imported > 0) saver?.request()
            return imported to duplicates
        }

        fun snapshot(): Map<String, Float> = synchronized(lock) {
            val out = HashMap<String, Float>()
            counts?.forEach { (w, v) ->
                if (v > 0) out[w] = (v / 25f).coerceIn(0f, 1f)
            }
            out
        }

        fun flush() {
            // Hold the lock across the write so a concurrent clear() can't be undone by an
            // in-flight save that finishes after the file was deleted.
            synchronized(lock) {
                if (!dirty) return
                val s = store ?: return
                val data = counts?.mapValues { it.value.toFloat() } ?: return
                if (s.save(data)) dirty = false
            }
        }

        fun clear(ctx: Context) {
            synchronized(lock) {
                counts?.clear()
                dirty = false
                version++
                (store ?: EncryptedMapFile(ctx, FILE, WordDictionary.USER_KEY_ALIAS)).delete()
            }
            ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** Flushes pending edits, then re-reads the encrypted file from disk. */
        fun reload(ctx: Context) = synchronized(lock) {
            flush()
            counts?.clear()
            counts = null
            ensureLoaded(ctx)
        }
    }
}
