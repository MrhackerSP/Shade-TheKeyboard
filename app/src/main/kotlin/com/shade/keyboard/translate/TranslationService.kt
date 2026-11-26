package com.shade.keyboard.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TranslationService {
    private const val CONNECT_MS = 6000
    private const val READ_MS = 8000
    private const val POST_MAX = 4500
    private const val GET_ENCODED_MAX = 4000
    private const val FREE_MAX = 450
    private const val OVERALL_MS = 20_000L

    suspend fun translate(text: String, source: String, target: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val src = if (source == "auto") guessSource(clean) else source
        val to = normalizeTarget(target)
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(OVERALL_MS) {
                val pieces = splitText(clean, POST_MAX)
                val done = arrayOfNulls<String>(pieces.size)
                for (i in pieces.indices) {
                    ensureActive()
                    val piece = pieces[i]
                    val body = piece.trim()
                    val trail = piece.takeLastWhile { it.isWhitespace() }
                    if (body.isEmpty()) { done[i] = trail; continue }
                    var translated: String? =
                        runCatching { viaTranslatePost(body, src, to).trim() }
                            .getOrNull()?.takeIf { it.isNotEmpty() }
                    if (translated == null &&
                        URLEncoder.encode(body, "UTF-8").length <= GET_ENCODED_MAX
                    ) {
                        translated = runCatching { viaTranslateGet(body, src, to).trim() }
                            .getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                    if (translated == null) {
                        translated = runCatching {
                            splitText(body, FREE_MAX).joinToString("") { sub ->
                                val t = sub.trim()
                                val tTrail = sub.takeLastWhile { it.isWhitespace() }
                                if (t.isEmpty()) tTrail
                                else viaFreeApi(t, src, to).trim().also {
                                    if (it.isEmpty()) throw IOException("free backend: empty result")
                                } + tTrail
                            }
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                    if (translated == null) return@withTimeoutOrNull null
                    done[i] = translated + trail
                }
                done.joinToString("")
            }
        }
    }

    private fun viaTranslatePost(text: String, from: String, to: String): String {
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

    private fun viaTranslateGet(text: String, from: String, to: String): String {
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

    private fun viaFreeApi(text: String, from: String, to: String): String {
        val src = if (from != "auto") from else guessSource(text)
        if (src == "auto") return ""
        val raw = open(
            "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
            "&langpair=" + URLEncoder.encode("$src|$to", "UTF-8")
        ).body()
        val root = JSONObject(raw)
        if (root.optInt("responseStatus") != 200) return ""
        return root.optJSONObject("responseData")?.optString("translatedText").orEmpty()
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
        text.any { it in '\u0900'..'\u097F' } -> "hi"
        text.any { it in '\u0400'..'\u04FF' } -> when {
            text.any { it in "іїєґІЇЄҐ" } -> "uk"
            text.any { it in "ђјљњћџЂЈЉЊЋЏ" } -> "sr"
            text.any { it == 'ъ' || it == 'Ъ' } && text.none { it in "ыэёЫЭЁ" } -> "bg"
            else -> "ru"
        }
        text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
        text.any { it in '\u3040'..'\u30FF' } -> "ja"
        text.any { it in '\uAC00'..'\uD7A3' } -> "ko"
        else -> "auto"
    }
}
