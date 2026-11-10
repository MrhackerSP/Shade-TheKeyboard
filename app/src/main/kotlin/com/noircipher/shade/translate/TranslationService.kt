package com.noircipher.shade.translate

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Translation service with multiple free backends, tried in order.
 * No API key required for any of them.
 *
 * 1. Google Translate (unofficial gtx endpoint, POST body, ~4500 chars/chunk)
 * 2. Google Translate (dict-chrome-ex endpoint, GET, ~1500 chars/chunk)
 * 3. MyMemory (free API, 450 chars/limit)
 *
 * Long text is split at sentence/paragraph boundaries and translated piece
 * by piece, then stitched back together.
 */
object TranslationService {

    private const val CONNECT_MS = 6000
    private const val READ_MS = 8000

    /**
     * Translate [text] from [source] to [target].
     * @param source Source language code, or "auto" for auto-detection.
     * @param target Target language code (e.g. "en", "es", "ru").
     * @return The translated text, or null on failure.
     */
    suspend fun translate(text: String, source: String, target: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return ""

        val src = if (source == "auto") guessSource(clean) else source
        val to = normalizeTarget(target)

        for ((limit, fn) in listOf(
            4500 to ::viaGtx,
            1500 to ::viaDictExt,
            450 to ::viaMyMemory
        )) {
            val result = runCatching {
                splitText(clean, limit).joinToString("") { piece ->
                    val trail = piece.takeLastWhile { it.isWhitespace() }
                    fn(piece.trim(), src, to).trim() + trail
                }
            }.getOrNull()

            if (!result.isNullOrEmpty()) return result
        }
        return null
    }

    private fun viaGtx(text: String, from: String, to: String): String {
        val conn = open(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&ie=UTF-8&oe=UTF-8"
        )
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        val body = conn.body()
        val parts = JSONArray(body).optJSONArray(0) ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) append(p.optString(0))
            }
        }
    }

    private fun viaDictExt(text: String, from: String, to: String): String {
        val raw = open(
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$from&tl=$to&q=" +
                URLEncoder.encode(text, "UTF-8")
        ).body()
        val root = JSONArray(raw)
        return buildString {
            for (i in 0 until root.length()) {
                when (val v = root.opt(i)) {
                    is String -> append(v)
                    is JSONArray -> append(v.optString(0))
                }
            }
        }
    }

    private fun viaMyMemory(text: String, from: String, to: String): String {
        val src = if (from != "auto") from else guessSource(text)
        val raw = open(
            "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
                "&langpair=$src|$to"
        ).body()
        val root = JSONArray(raw)
        return root.optJSONObject(0)?.optString("translatedText") ?: ""
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        }

    private fun HttpURLConnection.body(): String = try {
        if (responseCode !in 200..299) throw IOException("HTTP $responseCode")
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 2 * 1024 * 1024) throw IOException("Response too large")
            }
        }
        out.toString("UTF-8")
    } finally { disconnect() }

    internal fun splitText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val cut = listOf(
                    window.lastIndexOf('\n'), window.lastIndexOf(". "),
                    window.lastIndexOf("! "), window.lastIndexOf("? "),
                    window.lastIndexOf(' ')
                ).firstOrNull { it > max / 3 }
                if (cut != null) end = start + cut + 1
            }
            while (end < text.length && text[end].isWhitespace() && text[end] != '\n') end++
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    private fun normalizeTarget(target: String): String = when {
        target.startsWith("en-") -> "en"
        target == "zh" -> "zh-CN"
        target == "zh-tw" -> "zh-TW"
        else -> target
    }

    private fun guessSource(text: String): String = when {
        text.any { it in '\u0400'..'\u04FF' } -> if (text.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
        text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
        text.any { it in '\u3040'..'\u30FF' } -> "ja"
        text.any { it in '\uAC00'..'\uD7A3' } -> "ko"
        else -> "auto"
    }
}
