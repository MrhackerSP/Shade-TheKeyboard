package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val TAG = "Updater"
    // Change this to your GitHub repo: "username/Shade-TheKeyboard"
    private const val REPO = "MrhackerSP/Shade-TheKeyboard"
    private const val PREF_AUTO = "auto_update_check"

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val page: String
    )

    @Volatile
    var available: UpdateInfo? = null
        private set

    @Volatile
    var checking: Boolean = false
        private set

    @Volatile
    var progress: Float? = null
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var upToDate: Boolean = false
        private set

    private var autoChecked = false

    fun autoEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("shade_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_AUTO, true)
    }

    fun setAuto(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences("shade_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_AUTO, on).apply()
    }

    fun currentVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().substringBefore('-')
    }

    /** "1.2.3" > "1.2.2": compare version segments numerically. */
    internal fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Called once per app launch if auto-check is enabled. */
    suspend fun autoCheck(context: Context) {
        if (autoChecked || !autoEnabled(context)) return
        autoChecked = true
        check(context, quiet = true)
    }

    /** Check GitHub for the latest release. */
    suspend fun check(context: Context, quiet: Boolean = false) {
        if (checking) return
        checking = true
        error = null
        upToDate = false
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            val local = currentVersion(context)
            available = info?.takeIf { local.isNotEmpty() && newer(it.version, local) }
            upToDate = available == null
            if (!quiet && available != null) {
                Log.i(TAG, "Update available: ${available!!.version}")
            }
        } catch (e: Exception) {
            if (!quiet) error = "Could not check for updates"
            Log.w(TAG, "Update check failed", e)
        } finally {
            checking = false
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ShadeKeyboard")
        }
        val body = try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifEmpty { return null }
        val assets = o.optJSONArray("assets") ?: return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("debug", ignoreCase = true)) continue
            if (best == null || name.startsWith("Shade", ignoreCase = true)) best = a
        }
        val apk = best ?: return null
        return UpdateInfo(
            version = tag.removePrefix("v"),
            notes = o.optString("body").trim(),
            apkUrl = apk.optString("browser_download_url"),
            size = apk.optLong("size"),
            page = o.optString("html_url")
        )
    }

    /**
     * Download the APK and open the system installer.
     * Requires "Install unknown apps" permission on Android 8+.
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            error = "Allow update installation in settings, then try again"
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        error = null
        progress = 0f
        try {
            val file = withContext(Dispatchers.IO) { download(context, info) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            error = "Could not download update"
            Log.w(TAG, "Download failed", e)
        } finally {
            progress = null
        }
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Shade-${info.version}.apk")
        var url = URL(info.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        // Follow GitHub redirects manually
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ShadeKeyboard")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, next)
                continue
            }
            break
        }
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && out.length() != info.size) throw java.io.IOException("incomplete download")
        return out
    }
}
