package com.noircipher.shade

import com.noircipher.shade.slide.GlideLexicon
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

    @Volatile var bigramModel: BigramModel? = null

    // Feature 2: SymSpell index for O(1) typo lookup (replaces QWERTY neighbor scan)
    @Volatile
    private var symSpell: SymSpellIndex? = null

    // Feature 4: Rare word forms index — stops correction of real words like "happier", "walked"
    @Volatile
    private var rareWords: RareWordIndex? = null
    @Volatile
    private var bundledBigrams: BundledBigramModel? = null

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
            glideBundled = null

            // Feature 2: Build SymSpell index from the top 40K words
            // Build the index from words sorted by descending frequency so that a word's
            // index in the vocabulary IS its frequency rank (used by SymSpellIndex.rankOf
            // and the rank-based tie-breaks in SpatialCorrector).
            symSpell = SymSpellIndex(loaded.sortedByDescending { it.value }.map { it.key })

            // Feature 4: Load rare word forms (if available)
            rareWords = loadRareWords(localeId, loaded.size)
            bundledBigrams = loadBundledBigrams(localeId)
        }
    }

    // Feature 4: Load rare word forms from assets or filesDir
    private fun loadRareWords(localeId: String, firstRank: Int): RareWordIndex? {
        val id = localeId.split(',').firstOrNull()?.trim() ?: return null
        // Try cache first
        val cacheDir = java.io.File(ctx.cacheDir, "dict").apply { mkdirs() }
        val cacheFile = java.io.File(cacheDir, "${id}-rare.bin")
        RareWordIndex.load(cacheFile, firstRank)?.let { return it }
        // Try to read from assets or filesDir
        val candidates = listOf(
            java.io.File(ctx.filesDir, "dicts/${id}_more.txt"),
            java.io.File(ctx.filesDir, "dicts/${id}.more.txt")
        )
        for (f in candidates) {
            if (f.exists() && f.length() > 0) {
                val entries = RareWordIndex.parse(f.readLines())
                if (entries.isNotEmpty()) {
                    val idx = RareWordIndex(entries, firstRank)
                    runCatching { idx.save(cacheFile) }
                    return idx
                }
            }
        }
        // Try assets
        runCatching {
            ctx.assets.open("dicts/${id}_more.txt").bufferedReader().use { r ->
                val entries = RareWordIndex.parse(r.readLines())
                if (entries.isNotEmpty()) {
                    val idx = RareWordIndex(entries, firstRank)
                    runCatching { idx.save(cacheFile) }
                    return idx
                }
            }
        }
        return null
    }
    private fun loadBundledBigrams(localeId: String): BundledBigramModel? {
        val id = localeId.split(',').firstOrNull()?.trim() ?: return null
        // Try filesDir (downloaded)
        val f = java.io.File(ctx.filesDir, "dicts/${id}_bi.txt")
        if (f.exists() && f.length() > 0) {
            return runCatching { BundledBigramModel(f.readLines()) }.getOrNull()
        }
        // Try assets
        return runCatching {
            ctx.assets.open("dicts/${id}_bi.txt").bufferedReader().use { r ->
                BundledBigramModel(r.readLines())
            }
        }.getOrNull()
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

    /**
     * Build a QWERTY key position map for touch geometry.
     * Maps each character to its (row, col) position on the keyboard.
     * Used by TouchGeometry to compute substitution costs based on
     * where the finger actually landed.
     */
    private fun buildKeyPositions(): Map<Char, Pair<Float, Float>> {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val map = HashMap<Char, Pair<Float, Float>>(26)
        for ((rowIdx, row) in rows.withIndex()) {
            for ((colIdx, c) in row.withIndex()) {
                // Offset each row like a real QWERTY keyboard
                val xOffset = when (rowIdx) { 0 -> 0f; 1 -> 0.5f; 2 -> 0.75f; else -> 0f }
                map[c] = Pair(rowIdx.toFloat(), colIdx.toFloat() + xOffset)
            }
        }
        return map
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
        if (user.containsKey(w) || words.containsKey(w)) return true
        // Feature 4: check rare word forms (stops "happier" → "happen" correction)
        if (rareWords?.contains(w) == true) return true
        // Personal Dictionary: check Android's built-in user dictionary
        if (personalWords.contains(w)) return true
        return false
    }

    // ------------------------------------------------------------------ Personal Dictionary

    /** Words from Android's Personal Dictionary (Settings → Personal dictionary). */
    @Volatile
    private var personalWords: Set<String> = emptySet()

    @Volatile
    private var personalShortcuts: Map<String, String> = emptyMap()

    @Volatile
    private var personalLoadedAt = 0L

    /**
     * Reads Android's Personal Dictionary (UserDictionary.Words).
     * Available only to the active IME — called from ShadeService.
     * Refreshed at most once per 60 seconds.
     */
    fun loadPersonalDictionary(context: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        if (personalLoadedAt != 0L && now - personalLoadedAt < 60_000L) return
        personalLoadedAt = now
        val app = context.applicationContext
        Thread {
            try {
                val words = HashSet<String>()
                val shortcuts = HashMap<String, String>()
                app.contentResolver.query(
                    android.provider.UserDictionary.Words.CONTENT_URI,
                    arrayOf(
                        android.provider.UserDictionary.Words.WORD,
                        android.provider.UserDictionary.Words.LOCALE,
                        android.provider.UserDictionary.Words.SHORTCUT
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext() && words.size < 20_000) {
                        val w = c.getString(0)?.trim()?.lowercase() ?: continue
                        if (w.isEmpty() || w.length > 40) continue
                        words.add(w)
                        val sc = c.getString(2)?.trim()?.lowercase()
                        if (!sc.isNullOrEmpty()) shortcuts[sc] = w
                    }
                }
                personalWords = words
                personalShortcuts = shortcuts
            } catch (_: Exception) {
                // Personal dictionary not available — silently skip
            }
        }.apply { isDaemon = true; start() }
    }

    /** Check if a shortcut maps to a word in the personal dictionary. */
    fun personalShortcut(shortcut: String): String? = personalShortcuts[shortcut.lowercase()]

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

        // Snapshot the buckets inside the lock: learn() (main thread) mutates the user
        // buckets in place, and iterating the live ArrayList from a suggestion thread
        // throws ConcurrentModificationException mid-typing.
        val userBucket: List<String>? = synchronized(this) {
            (if (p.length >= 2) userPrefix2[p.substring(0, 2)] else userPrefix1[p.substring(0, 1)])?.toList()
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
            (if (p.length >= 2) prefix2[p.substring(0, 2)] else prefix1[p.substring(0, 1)])?.toList()
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
            val fuzzyBucket: List<String>? = synchronized(this) { prefix1[p.substring(0, 1)]?.toList() }
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
        return correction(word, isEnglishLocale, emptyList())
    }

    /**
     * Autocorrect with touch geometry.
     * [touches] is a list of per-character KeyTouch offsets. When available,
     * candidates are rescored by how well their letter positions match the
     * actual finger positions — edge hits correct confidently, center hits
     * resist correction.
     */
    @Synchronized
    fun correction(word: String, isEnglishLocale: Boolean = true, touches: List<TouchGeometry.KeyTouch> = emptyList()): String? {
        val w = word.lowercase()

        // English-only (Hinglish "id" must stay "id"), and never rewrite a genuine dictionary word
        // such as "ill" / "its" / "lets" unless it is a known contraction typo.
        if (isEnglishLocale) {
            contractions[w]?.let {
                if (!contains(w) || w in com.noircipher.shade.engine.MultiWordCorrector.SAFE_CONTRACTIONS) {
                    return match(word, it, isEnglishLocale)
                }
            }
        }

        if (w.length < 3 || !w.all { isWordChar(it) }) return null
        if (w.any { it.code > 0x24F }) return null
        if (contains(w)) return null  // also checks rare words now

        if (w.length > 20) return null

        // Feature 2: SymSpell — O(1) lookup via symmetric deletion index.
        // Catches typos in ANY position (including first letter) and 2-letter errors.
        val ss = symSpell
        if (ss != null) {
            val candidates = ss.similar(w)
            if (candidates.isNotEmpty()) {
                // Build key positions map for touch geometry (if touches available)
                val keyPositions = if (touches.isNotEmpty()) buildKeyPositions() else null
                var best: String? = null
                var bestScore = -1000f
                for (idx in candidates) {
                    val cand = ss.wordAt(idx)
                    if (cand == w) continue
                    if (cand.length !in (w.length - 2)..(w.length + 2)) continue
                    val freq = words[cand] ?: continue
                    val editCost = editCost(w, cand, Array(24) { FloatArray(24) })
                    var score = kotlin.math.ln(freq.toDouble()).toFloat() - 3f * editCost

                    // Touch geometry bonus: if we know where the finger was,
                    // rescore by how well the candidate's letters match the
                    // actual touch positions. A candidate whose letters are
                    // closer to where the finger landed gets a boost.
                    if (touches.isNotEmpty() && keyPositions != null) {
                        var touchBonus = 0f
                        val n = minOf(touches.size, cand.length, w.length)
                        for (i in 0 until n) {
                            if (w[i] != cand[i] && i < touches.size) {
                                val subCost = TouchGeometry.subCost(w[i], cand[i], touches[i], keyPositions)
                                // Lower subCost = candidate letter is closer to finger = better
                                touchBonus += (5f - subCost).coerceIn(-2f, 5f)
                            }
                        }
                        score += touchBonus * 0.3f
                    }

                    if (score > bestScore) {
                        best = cand
                        bestScore = score
                    }
                }
                return best?.let { match(word, it, isEnglishLocale) }
            }
        }

        // Fallback: A1 QWERTY neighbor scan (used if SymSpell is not built yet)
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
        // Merge what the user taught the keyboard with the bundled table; before this, the bundled
        // table returned early and learned bigrams were never consulted.
        val scores = LinkedHashMap<String, Float>()
        bigramModel?.predict(prev2, prev1, max * 2)?.forEach { (w, s) ->
            scores[w] = (scores[w] ?: 0f) + s
        }
        val gb = bundledBigrams
        if (gb != null && prev1 != null) {
            gb.next(prev1.lowercase()).take(max * 2).forEachIndexed { i, w ->
                scores[w] = (scores[w] ?: 0f) + 0.3f - 0.02f * i
            }
        }
        if (scores.isEmpty()) return emptyList()
        return scores.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    @Synchronized
    fun rerankWithContext(prev2: String?, prev1: String?, candidates: List<String>): List<String> {
        val gb = bundledBigrams
        if (gb != null && prev1 != null) {
            val p = prev1.lowercase()
            return candidates.sortedByDescending { cand ->
                val cost = gb.cost(p, cand.lowercase())
                if (cost >= 0f) -cost else -10f  // lower cost = higher score
            }
        }
        val model = bigramModel ?: return candidates
        return model.rerank(prev2, prev1, candidates)
    }

    /** Feature 5: expose bundled bigram cost for the neural reranker. */
    fun bundledBigramCost(prev: String, word: String): Float {
        return bundledBigrams?.cost(prev.lowercase(), word.lowercase()) ?: -1f
    }

    // ------------------------------------------------------------------ glide typing lexicons

    @Volatile private var glideBundled: GlideLexicon? = null
    @Volatile private var glideBundledFor: String? = null
    @Volatile private var glideUser: GlideLexicon = GlideLexicon.EMPTY
    private var glideUserVersion = -1L
    private var glideUserPersonal: Set<String>? = null

    /**
     * Swipeable view of the bundled words for the glide decoder. Built once per loaded language and
     * cached: it used to be rebuilt (185k pairs and logarithms, under the dictionary lock) on every
     * decode, i.e. every ~100 ms while swiping.
     */
    fun glideLexicon(): GlideLexicon {
        glideBundled?.let { if (glideBundledFor == loadedId) return it }
        val snap = synchronized(this) {
            val cached = glideBundled
            if (cached != null && glideBundledFor == loadedId) return cached
            val list = ArrayList<String>(words.size)
            val counts = LongArray(words.size)
            var i = 0
            for ((w, f) in words) { list.add(w); counts[i++] = f }
            Triple(loadedId, list, counts)
        }
        // The expensive part runs outside the lock, so typing and suggestions are never blocked.
        val built = GlideLexicon.build(snap.second, GlideLexicon.priorsFromCounts(snap.third))
        synchronized(this) {
            if (loadedId == snap.first) {
                glideBundled = built
                glideBundledFor = snap.first
            }
        }
        return built
    }

    /** Learned words and the Android personal dictionary; small, rebuilt only when they change. */
    fun glideUserLexicon(): GlideLexicon {
        val personal = personalWords
        val snap = synchronized(this) {
            if (glideUserVersion == userVersion && glideUserPersonal === personal) return glideUser
            Triple(userVersion, personal, HashMap(user))
        }
        val ws = ArrayList<String>(snap.third.size + snap.second.size)
        val pr = ArrayList<Float>(ws.size)
        for ((w, score) in snap.third) {
            ws.add(w)
            pr.add(0.55f + 0.4f * (score / (score + 4f)))   // learned words rank like fairly common ones
        }
        for (w in snap.second) {
            if (snap.third.containsKey(w)) continue
            ws.add(w)
            pr.add(0.6f)
        }
        val built = GlideLexicon.build(ws, pr.toFloatArray())
        synchronized(this) {
            glideUser = built
            glideUserVersion = snap.first
            glideUserPersonal = snap.second
        }
        return built
    }

    /** Records that [word] followed [prev1] (after [prev2]) without learning the word itself. */
    @Synchronized
    fun observeBigram(prev2: String?, prev1: String?, word: String) {
        if (prev1.isNullOrEmpty()) return
        bigramModel?.observe(prev2, prev1, word.lowercase())
    }

    /** Builds both glide lexicons ahead of time (call from a background thread). */
    fun warmGlide() {
        glideLexicon()
        glideUserLexicon()
    }

    /**
     * Likely next words after [prev1] (and [prev2]) as a 0..1 bonus map for the glide decoder, so
     * an ambiguous swipe is resolved by what was just typed. Empty without context.
     */
    fun nextWordScores(prev2: String?, prev1: String?, max: Int = 24): Map<String, Float> {
        if (prev1.isNullOrEmpty()) return emptyMap()
        val ranked = predictNext(prev2, prev1, max)
        if (ranked.isEmpty()) return emptyMap()
        val out = HashMap<String, Float>(ranked.size * 2)
        ranked.forEachIndexed { i, w -> out[w.lowercase()] = 1f - i.toFloat() / ranked.size }
        return out
    }

    /** Rank of a word in the frequency list (0 = most common, -1 = not found). */
    fun rankOf(word: String): Int {
        val ss = symSpell ?: return -1
        return ss.rankOf(word.lowercase())
    }

    /** SymSpell candidates for a word (deletion-based typo lookup). */
    fun symSpellCandidates(word: String): Set<String> {
        val ss = symSpell ?: return emptySet()
        return ss.similar(word.lowercase()).map { ss.wordAt(it) }.toSet()
    }

    /** Bigram prediction score: how likely is [word] after [prev]? Returns 0 if no data. */
    fun bigramPredictions(prev: String, word: String): Float {
        val model = bigramModel ?: return 0f
        return model.predict(null, prev, 32)
            .firstOrNull { it.first.equals(word, ignoreCase = true) }
            ?.second ?: 0f
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
