package com.noircipher.shade

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory dictionary with a prefix index for O(k) suggestions and
 * a prefix-bucket fuzzy scan for O(k) corrections.
 *
 * The prefix index maps 1- and 2-character prefixes to words starting
 * with them, sorted by frequency descending. Suggestions for a prefix
 * `p` become `index[p.take(2)].filter { it.startsWith(p) }`, which is
 * far cheaper than scanning the entire word set on every keystroke.
 *
 * Correction uses the same 2-char bucket as a filter, then checks edit
 * distance 1 within that bucket. Fast enough for a 50k-word dictionary
 * on mid-range devices.
 */
class WordDictionary(private val ctx: Context) {

    private val words: MutableMap<String, Long> = HashMap()
    private val prefix1: MutableMap<String, MutableList<String>> = HashMap()
    private val prefix2: MutableMap<String, MutableList<String>> = HashMap()
    private var loadedId: String? = null

    // Learned words live in ONE encrypted file (see EncryptedMapFile). The old
    // SharedPreferences store is only read once, to migrate legacy data.
    private val userStore = openUserStore(ctx)
    private val legacyPrefs: SharedPreferences = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private var user: MutableMap<String, Float> = HashMap()
    private var userDirty = false
    private var userVersion = 0L
    private var userStamp = -1L
    private val userSaver = DebouncedSaver(SAVE_DELAY_MS) { flushUser() }
    private val userPrefix1: MutableMap<String, MutableList<String>> = HashMap()
    private val userPrefix2: MutableMap<String, MutableList<String>> = HashMap()

    var bigramModel: BigramModel? = null

    private val contractions: Map<String, String> = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
        "werent" to "weren't", "hasnt" to "hasn't", "havent" to "haven't",
        "hadnt" to "hadn't", "didnt" to "didn't", "doesnt" to "doesn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "weve" to "we've",
        "its" to "it's", "thats" to "that's", "whats" to "what's",
        "heres" to "here's", "theres" to "there's", "wheres" to "where's",
        "hes" to "he's", "shes" to "she's", "lets" to "let's"
    )

    init { reloadUser() }

    suspend fun configure(localeId: String = "en") {
        if (loadedId == localeId) return
        val loaded = withContext(Dispatchers.IO) {
            val w = HashMap<String, Long>()
            for (id in localeId.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    readWordList(id, w)
                } catch (_: Exception) {
                    // missing list: skip it
                }
            }
            val top = if (w.size > MAX_WORDS) {
                w.entries.sortedByDescending { it.value }.take(MAX_WORDS)
            } else w.entries.toList()
            top
        }
        synchronized(this) {
            if (loadedId == localeId) return
            words.clear()
            for (e in loaded) words[e.key] = e.value
            rebuildPrefixIndex()
            loadedId = localeId
        }
    }

    private fun readWordList(id: String, w: MutableMap<String, Long>) {
        val candidates = listOf(
            "languages/$id/dictionary.txt",
            "wordlists/$id.txt"
        )
        var lastError: Exception? = null
        for (path in candidates) {
            try {
                val raw = java.io.BufferedInputStream(ctx.assets.open(path))
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val stream: java.io.InputStream =
                    if (b0 == 0x1f && b1 == 0x8b) java.util.zip.GZIPInputStream(raw) else raw
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val t = line.trim()
                        if (t.isEmpty()) continue
                        val sp = t.indexOfFirst { it == ' ' || it == '\t' }
                        val word = (if (sp < 0) t else t.substring(0, sp)).lowercase()
                        val freq = if (sp < 0) 1L else (t.substring(sp + 1).trim().toLongOrNull() ?: continue)
                        if (word.length > 20) continue
                        if (word.length == 1 && word[0] in 'a'..'z' && word != "a" && word != "i") continue
                        if (!word.all { isWordChar(it) }) continue
                        w[word] = maxOf(w[word] ?: 0L, freq)
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No dictionary asset found for $id")
    }

    private fun rebuildPrefixIndex() {
        prefix1.clear()
        prefix2.clear()
        for ((word, freq) in words) {
            if (word.isEmpty()) continue
            prefix1.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
            if (word.length >= 2) {
                prefix2.getOrPut(word.substring(0, 2)) { ArrayList() }.add(word)
            }
        }
        for (list in prefix1.values) list.sortByDescending { words[it] ?: 0L }
        for (list in prefix2.values) list.sortByDescending { words[it] ?: 0L }
    }

    private fun rebuildUserPrefixIndex() {
        userPrefix1.clear()
        userPrefix2.clear()
        for ((word, _) in user) {
            if (word.isEmpty()) continue
            userPrefix1.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
            if (word.length >= 2) {
                userPrefix2.getOrPut(word.substring(0, 2)) { ArrayList() }.add(word)
            }
        }
        for (list in userPrefix1.values) list.sortByDescending { user[it] ?: 0f }
        for (list in userPrefix2.values) list.sortByDescending { user[it] ?: 0f }
    }

    /**
     * Re-reads the learned words if the file changed on disk (backup restore,
     * "clear learned data"). Unsaved in-memory changes always win.
     */
    @Synchronized
    fun reloadUser() {
        if (userDirty) return
        val stamp = userStore.stamp()
        if (stamp == userStamp) return
        user = userStore.load() ?: migrateLegacyUsers()
        userStamp = userStore.stamp()
        rebuildUserPrefixIndex()
    }

    /** One-time import of the old SharedPreferences store (random-IV ciphertext keys). */
    private fun migrateLegacyUsers(): MutableMap<String, Float> {
        val all = legacyPrefs.all
        if (all.isEmpty()) return HashMap()
        val migrated = UserWordCodec.fromLegacyEntries(all) { key ->
            SecureStore.decrypt(USER_KEY_ALIAS, key) ?: key
        }
        // Only drop the legacy data once the encrypted copy is safely on disk.
        if (migrated.isEmpty() || userStore.save(migrated)) {
            legacyPrefs.edit().clear().apply()
        }
        return migrated
    }

    private fun markUserDirty() {
        userDirty = true
        userVersion++
        userSaver.request()
    }

    /** Writes pending learned words (background thread). Safe to call any time. */
    fun flushUser() {
        val snapshot: Map<String, Float>
        val version: Long
        synchronized(this) {
            if (!userDirty) return
            snapshot = HashMap(user)
            version = userVersion
        }
        if (userStore.save(snapshot)) {
            synchronized(this) {
                if (userVersion == version) userDirty = false
                userStamp = userStore.stamp()
            }
        }
    }

    /** Flushes learned words and n-grams on a background thread; never blocks the caller. */
    fun shutdown() {
        userSaver.shutdown()
        val bm = bigramModel ?: return
        Thread { try { bm.save() } catch (_: Exception) {} }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return user.containsKey(w) || words.containsKey(w)
    }

    @Synchronized
    fun learn(word: String, prev2: String? = null, prev1: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { isWordChar(it) }) return
        val old = user[w] ?: 0f
        val score = old * DECAY + 1f
        user[w] = score
        pruneUserWordsIfNeeded()
        markUserDirty()

        if (w.isNotEmpty()) {
            val k1 = w.substring(0, 1)
            val l1 = userPrefix1.getOrPut(k1) { ArrayList() }
            if (w !in l1) l1.add(w)
            if (w.length >= 2) {
                val k2 = w.substring(0, 2)
                val l2 = userPrefix2.getOrPut(k2) { ArrayList() }
                if (w !in l2) l2.add(w)
            }
        }
        bigramModel?.observe(prev2, prev1, w)
    }

    /** Keeps the learned-word list bounded: drops the lowest-scored 10% when it grows too large. */
    private fun pruneUserWordsIfNeeded() {
        if (user.size <= MAX_USER_WORDS) return
        val drop = user.entries.sortedBy { it.value }.take(user.size / 10).map { it.key }
        for (w in drop) user.remove(w)
        rebuildUserPrefixIndex()
    }

    fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val known = synchronized(this) { contains(p) }
        val scored = HashMap<String, Float>()

        synchronized(this) { contractions[p] }?.let {
            scored[it.lowercase()] = if (known) 12f else 100f
        }

        val userBucket: List<String>? = synchronized(this) {
            if (p.length >= 2) userPrefix2[p.substring(0, 2)] else userPrefix1[p.substring(0, 1)]
        }
        val userFreq = synchronized(this) {
            userBucket.orEmpty().mapNotNull { cand -> user[cand]?.let { cand to it } }.toMap()
        }
        var found = 0
        userBucket?.let { bucket ->
            for (cand in bucket) {
                if (!cand.startsWith(p)) continue
                val us = userFreq[cand] ?: continue
                val sc = 16f + kotlin.math.ln(us + 1f) - 0.3f * (cand.length - p.length)
                scored[cand] = maxOf(scored[cand] ?: -1000f, sc)
                if (++found >= 6) break
            }
        }

        var completions = 0
        val bucket: List<String>? = synchronized(this) {
            if (p.length >= 2) prefix2[p.substring(0, 2)] else prefix1[p.substring(0, 1)]
        }
        val freq = synchronized(this) {
            bucket.orEmpty().mapNotNull { cand -> words[cand]?.let { cand to it } }.toMap()
        }
        bucket?.let { b ->
            for (cand in b) {
                if (!cand.startsWith(p)) continue
                val f = freq[cand] ?: continue
                val sc = kotlin.math.ln(f.toDouble()).toFloat() - 0.3f * (cand.length - p.length)
                scored[cand] = maxOf(scored[cand] ?: -1000f, sc)
                if (++completions >= 12) break
            }
        }

        if (p.length in 3..20 && (!known || completions < max)) {
            val limit = if (p.length <= 4) 1.0f else 1.6f
            val fuzzyBucket: List<String>? = synchronized(this) { prefix1[p.substring(0, 1)] }
            val fuzzyFreq = synchronized(this) {
                fuzzyBucket.orEmpty().mapNotNull { cand -> words[cand]?.let { cand to it } }.toMap()
            }
            if (fuzzyBucket != null) {
                val d = Array(24) { FloatArray(24) }
                for (cand in fuzzyBucket) {
                    if (cand.length < p.length - 1 || cand.length > p.length + 1) continue
                    if (scored.containsKey(cand)) continue
                    val cost = editCost(p, cand, d)
                    if (cost > limit) continue
                    val f = fuzzyFreq[cand] ?: continue
                    scored[cand] = kotlin.math.ln(f.toDouble()).toFloat() - 4f * cost
                }
            }
        }

        return scored.entries
            .sortedByDescending { it.value }
            .take(max)
            .map { match(prefix, it.key) }
    }

    private val adj: Array<BooleanArray> = Array(26) { BooleanArray(26) }

    init {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r1 in rows.indices) for (c1 in rows[r1].indices) {
            for (r2 in rows.indices) for (c2 in rows[r2].indices) {
                if ((r1 != r2 || c1 != c2) &&
                    kotlin.math.abs(r1 - r2) <= 1 && kotlin.math.abs(c1 - c2) <= 1
                ) {
                    adj[rows[r1][c1] - 'a'][rows[r2][c2] - 'a'] = true
                }
            }
        }
    }

    /** Typo distance: neighbouring keys and swapped letters cost less than random mistakes. */
    private fun editCost(a: String, b: String, d: Array<FloatArray>): Float {
        val n = a.length
        val m = b.length
        for (i in 0..n) d[i][0] = i.toFloat()
        for (j in 0..m) d[0][j] = j.toFloat()
        for (i in 1..n) {
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = when {
                    ca == cb -> 0f
                    ca in 'a'..'z' && cb in 'a'..'z' && adj[ca - 'a'][cb - 'a'] -> 0.6f
                    else -> 1f
                }
                var v = minOf(d[i - 1][j] + 1f, d[i][j - 1] + 1f, d[i - 1][j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    v = minOf(v, d[i - 2][j - 2] + 0.7f)
                }
                d[i][j] = v
            }
        }
        return d[n][m]
    }

    /**
     * QWERTY-adjacent keys for a given character.
     *
     * A1 — used by [correction] to widen the candidate scan to neighboring
     * prefix buckets. If the user typed 'v' but meant 'b' or 'f' (typical
     * QWERTY slips), we still want to find a candidate.
     */
    fun qwertyNeighbors(c: String): List<String> {
        if (c.isEmpty()) return emptyList()
        val ch = c.lowercase()[0]
        if (ch !in 'a'..'z') return emptyList()
        val out = ArrayList<String>(4)
        for (other in 'a'..'z') {
            if (other == ch) continue
            if (adj[ch - 'a'][other - 'a']) out.add(other.toString())
        }
        return out
    }

    @Synchronized
    fun correction(word: String, isEnglishLocale: Boolean = true): String? {
        val w = word.lowercase()

        contractions[w]?.let { return match(word, it, isEnglishLocale) }

        if (w.length < 3 || !w.all { isWordChar(it) }) return null
        if (w.any { it.code > 0x24F }) return null
        if (contains(w)) return null

        if (w.length > 20) return null

        // A1 — search the typed first-letter's bucket PLUS neighbor buckets.
        // Neighbor-bucket candidates get a 0.5f penalty so they only win
        // when the typed bucket yields nothing better.
        val firstChar = w.substring(0, 1)
        val ownBucket: List<String> = prefix1[firstChar] ?: emptyList()
        val neighborBuckets = qwertyNeighbors(firstChar).mapNotNull { prefix1[it] }

        var best: String? = null
        var bestScore = -1000f
        val d = Array(24) { FloatArray(24) }

        for (cand in ownBucket) {
            if (cand.length !in (w.length - 1)..(w.length + 1)) continue
            val freq = words[cand] ?: continue
            if (!within1(w, cand)) continue
            val score = kotlin.math.ln(freq.toDouble()).toFloat() - 3f * editCost(w, cand, d)
            if (score > bestScore) {
                best = cand
                bestScore = score
            }
        }
        for (bucket in neighborBuckets) {
            for (cand in bucket) {
                if (cand.length !in (w.length - 1)..(w.length + 1)) continue
                val freq = words[cand] ?: continue
                if (!within1(w, cand)) continue
                val score = kotlin.math.ln(freq.toDouble()).toFloat() - 3f * editCost(w, cand, d) - 0.5f
                if (score > bestScore) {
                    best = cand
                    bestScore = score
                }
            }
        }
        return best?.let { match(word, it, isEnglishLocale) }
    }

    private fun within1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (a == b || kotlin.math.abs(la - lb) > 1) return false
        if (la == lb) {
            var i = 0
            while (i < la && a[i] == b[i]) i++
            if (i == la) return false
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] &&
                a.substring(i + 2) == b.substring(i + 2)
        }
        val s = if (la < lb) a else b
        val l = if (la < lb) b else a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }

    private fun match(typed: String, w: String, isEnglishLocale: Boolean = true): String {
        // A2 — gate the i→I capitalization on English locale. Other locales
        // (Romanized Hindi, Hinglish) treat standalone 'i' as a real word.
        if (isEnglishLocale && (w == "i" || w.startsWith("i'"))) return "I" + w.substring(1)
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> w.uppercase()
            typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
    }

    @Synchronized
    fun predictNext(prev2: String?, prev1: String?, max: Int): List<String> {
        val model = bigramModel ?: return emptyList()
        return model.predict(prev2, prev1, max).map { it.first }
    }

    @Synchronized
    fun rerankWithContext(prev2: String?, prev1: String?, candidates: List<String>): List<String> {
        val model = bigramModel ?: return candidates
        return model.rerank(prev2, prev1, candidates)
    }

    suspend fun suggestAsync(prefix: String, max: Int): List<String> =
        withContext(Dispatchers.Default) { suggest(prefix, max) }

    suspend fun correctionAsync(word: String, isEnglishLocale: Boolean = true): String? =
        withContext(Dispatchers.Default) { synchronized(this) { correction(word, isEnglishLocale) } }

    @Synchronized
    fun exportUser(): Map<String, Float> = HashMap(user)

    companion object {
        private const val DECAY = 0.99f
        private const val MAX_WORDS = 300000
        private const val MAX_USER_WORDS = 20000
        private const val SAVE_DELAY_MS = 2000L
        private const val USER_FILE = "user_words.enc"
        const val USER_KEY_ALIAS = "shade_user_words_key"

        fun openUserStore(ctx: Context) = EncryptedMapFile(ctx, USER_FILE, USER_KEY_ALIAS)

        /** Letters, apostrophes and combining marks (needed for Hindi vowel signs). */
        fun isWordChar(c: Char): Boolean = UserWordCodec.isWordChar(c)

        fun clearLearned(ctx: Context) {
            openUserStore(ctx).delete()
            ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
