package com.noircipher.shade.gif

import android.net.Uri
import com.noircipher.shade.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * KLIPY GIF API (https://docs.klipy.com). The key is baked into the build (see `KLIPY_API_KEY` in
 * app/build.gradle.kts) when provided; otherwise the person can paste their own free key once in
 * the GIF panel, which is stored in preferences ("gif_api_key") and used at runtime.
 */
object KlipyConfig {
    val apiKey: String get() = BuildConfig.KLIPY_API_KEY

    /** Resolves the key to use: the build-time key wins, then the runtime key from prefs. */
    fun effectiveKey(runtime: String?): String =
        apiKey.ifBlank { runtime?.trim().orEmpty() }

    fun isConfigured(runtime: String?): Boolean = effectiveKey(runtime).isNotBlank()
}

/** One downloadable rendition of a GIF. */
data class MediaRef(val url: String, val width: Int, val height: Int, val format: String) {
    /** MIME type to hand to an app that accepts rich content. */
    val mime: String
        get() = when (format) {
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "image/gif"
        }
}

data class GifItem(
    val slug: String,
    val title: String,
    /** Small rendition shown in the picker. */
    val preview: MediaRef,
    /** Rendition that is inserted into the text field. */
    val send: MediaRef
)

data class GifPage(val items: List<GifItem>, val page: Int, val hasNext: Boolean)

/**
 * Turns KLIPY's JSON into [GifPage]s. Pure (org.json only) so it is unit-testable.
 *
 * Response shape: `{"result":true,"data":{"data":[...],"current_page":1,"per_page":24,"has_next":true}}`.
 * Every item carries its renditions under `file` as `{size: {format: {url,width,height}}}` with sizes
 * such as `hd`, `md`, `sm`, `xs` and formats such as `gif`, `webp`, `jpg`. A flat `{format: {...}}`
 * map is accepted too. Sponsored items (`kind`/`type` = "ad") are dropped, the keyboard does not
 * display ads.
 */
object KlipyParser {
    private val PREVIEW_SIZES = listOf("sm", "xs", "md", "hd")
    private val SEND_SIZES = listOf("md", "hd", "sm")
    private val SEND_FORMATS = listOf("gif", "webp")

    fun parsePage(json: String, animatedPreviews: Boolean): GifPage {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val arr = data.optJSONArray("data") ?: data.optJSONArray("results")
        val previewFormats =
            if (animatedPreviews) listOf("webp", "gif", "jpg") else listOf("jpg", "gif", "webp")
        val items = ArrayList<GifItem>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") == "ad" || o.optString("type") == "ad") continue
                val file = o.optJSONObject("file") ?: o.optJSONObject("files") ?: continue
                val preview = pick(file, PREVIEW_SIZES, previewFormats, sizesFirst = true)
                val send = pick(file, SEND_SIZES, SEND_FORMATS, sizesFirst = false)
                if (preview == null || send == null) continue
                val slug = o.optString("slug").ifBlank { o.optString("id") }
                if (slug.isBlank()) continue
                items.add(GifItem(slug, o.optString("title"), preview, send))
            }
        }
        val page = data.optInt("current_page", 1)
        val hasNext = data.optBoolean("has_next", false)
        return GifPage(items, page, hasNext)
    }

    /**
     * Picks a rendition. [sizesFirst] walks sizes in priority order and takes the first format
     * found (smallest preview wins); otherwise formats are the priority and any size will do
     * (a GIF beats a WebP, then the preferred size within that format).
     */
    internal fun pick(
        file: JSONObject,
        sizes: List<String>,
        formats: List<String>,
        sizesFirst: Boolean
    ): MediaRef? {
        fun ref(o: JSONObject?, format: String): MediaRef? {
            val fmt = o?.optJSONObject(format) ?: return null
            val url = fmt.optString("url")
            if (!url.startsWith("https://")) return null
            return MediaRef(url, fmt.optInt("width"), fmt.optInt("height"), format)
        }
        if (sizesFirst) {
            for (s in sizes) for (f in formats) ref(file.optJSONObject(s), f)?.let { return it }
        } else {
            for (f in formats) for (s in sizes) ref(file.optJSONObject(s), f)?.let { return it }
        }
        // Flat layout: file.{format}
        for (f in formats) ref(file, f)?.let { return it }
        return null
    }
}

class KlipyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Blocking client; call from a background dispatcher.
 *
 * @param customerId random per-install id (KLIPY asks for a stable, anonymous user id).
 */
class KlipyClient(private val apiKey: String, private val customerId: String) {

    fun trending(page: Int, perPage: Int = PER_PAGE): GifPage = fetch("trending", null, page, perPage)

    fun search(query: String, page: Int, perPage: Int = PER_PAGE): GifPage =
        fetch("search", query, page, perPage)

    private fun fetch(action: String, query: String?, page: Int, perPage: Int): GifPage {
        val sb = StringBuilder(BASE).append('/').append(Uri.encode(apiKey)).append("/gifs/").append(action)
        sb.append("?page=").append(page.coerceAtLeast(1))
        sb.append("&per_page=").append(perPage.coerceIn(8, 50))
        sb.append("&customer_id=").append(Uri.encode(customerId))
        if (query != null) sb.append("&q=").append(Uri.encode(query))
        val body = HttpFetch.getString(sb.toString())
        return try {
            KlipyParser.parsePage(body, animatedPreviews = android.os.Build.VERSION.SDK_INT >= 28)
        } catch (e: Exception) {
            throw KlipyException("Unreadable response", e)
        }
    }

    /** Tells KLIPY a GIF was sent (their "share trigger"). Best effort, errors are ignored. */
    fun registerShare(slug: String, query: String?) {
        try {
            val url = "$BASE/${Uri.encode(apiKey)}/gifs/share/${Uri.encode(slug)}"
            val json = JSONObject().put("customer_id", customerId)
            if (!query.isNullOrBlank()) json.put("q", query)
            HttpFetch.postJson(url, json.toString())
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val BASE = "https://api.klipy.com/api/v1"
        const val PER_PAGE = 24
    }
}

/** Tiny HTTPS helper. Plain HTTP is refused (the app's network config blocks it as well). */
object HttpFetch {
    private const val CONNECT_MS = 8000
    private const val READ_MS = 12000
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024

    private fun open(url: String, method: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw KlipyException("Refusing non-HTTPS URL")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShadeKeyboard")
            setRequestProperty("Accept", "application/json")
        }
    }

    fun getString(url: String): String {
        val conn = open(url, "GET")
        try {
            if (conn.responseCode !in 200..299) throw KlipyException("HTTP ${conn.responseCode}")
            return String(readLimited(conn, MAX_JSON_BYTES), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(url: String, body: String) {
        val conn = open(url, "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode // force the request
        } finally {
            conn.disconnect()
        }
    }

    fun getBytes(url: String, maxBytes: Int): ByteArray {
        val conn = open(url, "GET")
        conn.setRequestProperty("Accept", "image/*")
        try {
            if (conn.responseCode !in 200..299) throw KlipyException("HTTP ${conn.responseCode}")
            return readLimited(conn, maxBytes)
        } finally {
            conn.disconnect()
        }
    }

    fun downloadTo(url: String, file: File, maxBytes: Int): Boolean {
        return try {
            file.writeBytes(getBytes(url, maxBytes))
            true
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    private fun readLimited(conn: HttpURLConnection, max: Int): ByteArray {
        val declared = conn.contentLength
        if (declared > max) throw KlipyException("Response too large")
        val out = ByteArrayOutputStream(if (declared > 0) declared else 16 * 1024)
        conn.inputStream.use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw KlipyException("Response too large")
            }
        }
        return out.toByteArray()
    }
}
