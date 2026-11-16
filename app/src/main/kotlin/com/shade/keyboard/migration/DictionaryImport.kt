package com.shade.keyboard.migration

import android.content.Context
import android.net.Uri
import com.shade.keyboard.UserWordCodec
import com.shade.keyboard.engine.UserWordMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Imports learned words from files exported by other keyboards or dictionary
 * apps, so switching to this keyboard keeps the words it had learned.
 *
 * No format assumption is made beyond "the file contains words somewhere":
 *
 *  - ZIP archives are unpacked and every text entry inside is parsed (backups
 *    that bundle settings, models and dictionary files in one export work);
 *  - JSON is walked recursively: arrays of strings, word-to-frequency maps and
 *    objects with well-known word-bearing keys ("word", "phrase", "term", ...)
 *    with optional sibling frequency fields are all recognised;
 *  - plain text is parsed as one word per line, with an optional trailing
 *    frequency ("word 12" or "word<TAB>12").
 *
 * Everything funnels into [UserWordMemory]; words already learned are counted
 * as duplicates and skipped, so repeated imports are harmless.
 */
object DictionaryImport {

    /** Result of one import run. */
    data class Report(
        val imported: Int,
        val duplicates: Int,
        val invalid: Int,
        val sources: List<String>
    ) {
        val total: Int get() = imported + duplicates
        val isEmpty: Boolean get() = total == 0
    }

    /** Words collected from a file: word -> strongest frequency hint (>= 1). */
    class Harvest internal constructor() {
        val words = LinkedHashMap<String, Int>()
        val sources = LinkedHashSet<String>()
        var invalid: Int = 0
            private set

        internal fun add(word: String, freq: Int) {
            val w = normalize(word) ?: run { invalid++; return }
            if (freq > (words[w] ?: 0)) words[w] = freq
        }

        internal companion object {
            fun normalize(word: String): String? {
                val w = word.trim().lowercase()
                if (w.isEmpty()) return null
                // Drop a trailing lone apostrophe; keep internal ones ("don't").
                val candidate = if (w.endsWith("'")) w.dropLast(1) else w
                if (!UserWordCodec.isValidWord(candidate)) return null
                return candidate
            }
        }
    }

    private const val MAX_JSON_DEPTH = 8
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 512
    private const val MAX_WORDS = 20000

    /** Entry point used from settings: reads, parses and imports whatever [uri] points at. */
    fun importFromUri(context: Context, uri: Uri): Report {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_FILE_BYTES) throw IllegalArgumentException("file too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return Report(0, 0, 0, listOf("unreadable"))
        val harvest = parseBytes(bytes)
        return apply(context, harvest)
    }

    /**
     * Pure parsing layer (JVM-testable): sniffs the bytes - zip magic, JSON,
     * plain text - and harvests every word found.
     */
    fun parseBytes(bytes: ByteArray): Harvest {
        val harvest = Harvest()
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 3.toByte() || bytes[2] == 5.toByte() || bytes[2] == 7.toByte())
        ) {
            harvest.sources.add("zip")
            importZip(bytes, harvest)
        } else if (looksLikeJson(bytes)) {
            importJson(String(bytes, Charsets.UTF_8), harvest, "json")
        } else {
            importText(String(bytes, Charsets.UTF_8), harvest, "text")
        }
        return harvest
    }

    // ------------------------------------------------------------------ zip

    private fun importZip(bytes: ByteArray, harvest: Harvest) {
        var entries = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val lower = name.lowercase()
                if (!lower.endsWith(".json") && !lower.endsWith(".txt") &&
                    !lower.endsWith(".text") && !lower.endsWith(".csv") && !lower.endsWith(".tsv")
                ) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = zip.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_ZIP_ENTRY_BYTES) break
                    out.write(buf, 0, n)
                }
                val data = out.toByteArray()
                if (looksLikeJson(data)) {
                    importJson(String(data, Charsets.UTF_8), harvest, name)
                } else {
                    importText(String(data, Charsets.UTF_8), harvest, name)
                }
            }
        }
    }

    // ------------------------------------------------------------------ json

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size && bytes[i].toInt() in 9..32) i++
        if (i >= bytes.size) return false
        return bytes[i] == '{'.code.toByte() || bytes[i] == '['.code.toByte()
    }

    private fun importJson(text: String, harvest: Harvest, source: String) {
        val before = harvest.words.size
        val root = try {
            val t = text.trim()
            if (t.startsWith("{")) JSONObject(t) else JSONArray(t)
        } catch (t: Throwable) {
            return
        }
        // A root array is a word list by convention; inside an object, strings are
        // only harvested under known word-bearing keys (settings blobs stay clean).
        collectJson(root, harvest, 0, freqHint = null, allowStrings = root is JSONArray)
        if (harvest.words.size > before) harvest.sources.add(source)
    }

    /** Object keys whose string (or array) value is a dictionary word / word list. */
    private val WORD_KEYS = setOf(
        "word", "words", "phrase", "phrases", "term", "terms",
        "entry", "entries", "dictionary", "user_dict", "userdictionary", "user_words",
        "learned", "text", "value", "name"
    )

    /** Sibling keys that may carry a frequency / weight for the word. */
    private val FREQ_KEYS = listOf("freq", "frequency", "weight", "count", "score", "v")

    private fun collectJson(node: Any?, harvest: Harvest, depth: Int, freqHint: Int?, allowStrings: Boolean) {
        if (depth > MAX_JSON_DEPTH || harvest.words.size >= MAX_WORDS) return
        when (node) {
            is String -> if (allowStrings) harvest.add(node, freqHint ?: 1)
            is JSONObject -> {
                var captured = false
                for (key in WORD_KEYS) {
                    val v = node.opt(key) ?: continue
                    when (v) {
                        is String -> {
                            harvest.add(v, siblingFreq(node) ?: freqHint ?: 1)
                            captured = true
                        }
                        is JSONArray -> {
                            val f = siblingFreq(node) ?: freqHint
                            for (k in 0 until v.length()) {
                                collectJson(v.opt(k), harvest, depth + 1, f, allowStrings = true)
                            }
                            captured = true
                        }
                    }
                }
                if (captured) return
                // A bare word->frequency map: string keys with numeric values.
                val keys = node.names() ?: return
                var numeric = 0
                for (k in 0 until keys.length()) {
                    if (node.opt(keys.getString(k)) is Number) numeric++
                }
                if (numeric > 0 && numeric == keys.length()) {
                    for (k in 0 until keys.length()) {
                        val key = keys.getString(k)
                        harvest.add(key, node.optDouble(key, 1.0).toInt().coerceAtLeast(1))
                    }
                    return
                }
                for (k in 0 until keys.length()) {
                    collectJson(node.opt(keys.getString(k)), harvest, depth + 1, null, allowStrings = false)
                }
            }
            is JSONArray -> {
                val stringsHere = allowStrings || depth == 0
                for (k in 0 until node.length()) {
                    collectJson(node.opt(k), harvest, depth + 1, null, allowStrings = stringsHere)
                }
            }
        }
    }

    private fun siblingFreq(obj: JSONObject): Int? {
        for (key in FREQ_KEYS) {
            val v = obj.opt(key)
            if (v is Number && v.toDouble() >= 1.0) return v.toInt().coerceAtMost(1000)
        }
        return null
    }

    // ------------------------------------------------------------------ plain text

    private fun importText(text: String, harvest: Harvest, source: String) {
        val before = harvest.words.size
        for (rawLine in text.lineSequence()) {
            if (harvest.words.size >= MAX_WORDS) break
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            if (line.contains("://")) continue  // URLs and embedded links are not words
            val sep = line.indexOf('\t')
            val pair = if (sep > 0) {
                val rest = line.substring(sep + 1).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                line.substring(0, sep).trim() to (rest.toIntOrNull() ?: 1)
            } else {
                val parts = line.split(Regex("\\s+"))
                val trailing = if (parts.size >= 2) parts.last().toIntOrNull() else null
                if (trailing != null) {
                    parts.dropLast(1).joinToString(" ") to trailing
                } else {
                    line to 1
                }
            }
            harvest.add(pair.first, pair.second.coerceAtLeast(1))
        }
        if (harvest.words.size > before) harvest.sources.add(source)
    }

    // ------------------------------------------------------------------ apply

    /** Pushes a harvest into the learned-word store. Call on a worker thread. */
    fun apply(context: Context, harvest: Harvest): Report {
        val (imported, duplicates) = UserWordMemory(context).importWords(harvest.words)
        return Report(imported, duplicates, harvest.invalid, harvest.sources.toList())
    }
}
