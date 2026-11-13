package com.noircipher.shade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val TAG = "Updater"
    // Change this to your GitHub repo: "username/Shade-TheKeyboard"
    private const val REPO = "noircipher/shade"
    private const val PREF_AUTO = "auto_update_check"

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val page: String,
        val sha256: String = "" // lowercase hex from the release asset digest, "" if GitHub didn't provide one
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

    // The Settings switch writes to the default preferences file; this used to read a different
    // file ("shade_prefs"), so the toggle did nothing. Off by default: no network unless opted in.
    fun autoEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO, false)

    fun setAuto(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_AUTO, on).apply()
    }

    private class NoReleaseException : java.io.IOException("No release published")

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
            if (!quiet) error = if (e is NoReleaseException) "No update has been published yet" else "Could not check for updates"
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
            if (conn.responseCode == 404) throw NoReleaseException()
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifEmpty { throw NoReleaseException() }
        val assets = o.optJSONArray("assets") ?: throw NoReleaseException()
        // Pick deterministically: prefer "Shade*" names (first one wins instead of the
        // last matching asset overwriting it, which made the choice depend on the
        // order GitHub happened to list the release assets in).
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("debug", ignoreCase = true)) continue
            if (best == null) best = a
            else if (name.startsWith("Shade", ignoreCase = true) &&
                !best.optString("name").startsWith("Shade", ignoreCase = true)) best = a
        }
        val apk = best ?: throw NoReleaseException()
        val apkUrl = apk.optString("browser_download_url")
        // Only ever download over HTTPS from GitHub.
        if (!apkUrl.startsWith("https://github.com/")) throw java.io.IOException("Unexpected download URL")
        return UpdateInfo(
            version = tag.removePrefix("v"),
            notes = o.optString("body").trim(),
            apkUrl = apkUrl,
            size = apk.optLong("size"),
            page = o.optString("html_url"),
            sha256 = apk.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 } ?: ""
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
            val file = withContext(Dispatchers.IO) {
                download(context, info).also { verifyApk(context, it) }
            }
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
        val sha = MessageDigest.getInstance("SHA-256")
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
                if (url.protocol != "https") throw java.io.IOException("Refusing non-HTTPS redirect")
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
                        sha.update(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && out.length() != info.size) {
            out.delete()
            throw java.io.IOException("incomplete download")
        }
        if (info.sha256.isNotEmpty()) {
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (actual != info.sha256) {
                out.delete()
                throw java.io.IOException("checksum mismatch")
            }
        }
        return out
    }

    /**
     * The downloaded file must be this very app (same package), newer, and signed with the same
     * certificate as the installed copy. Android's installer enforces the signature too, but
     * failing here avoids offering a file that can never install.
     */
    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        if (archive == null || archive.packageName != context.packageName) {
            file.delete(); throw java.io.IOException("not a Shade APK")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun sigs(p: android.content.pm.PackageInfo): Set<android.content.pm.Signature> =
            if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toSet().orEmpty()
            else p.signatures?.toSet().orEmpty()
        val a = sigs(archive)
        if (a.isEmpty() || a != sigs(installed)) {
            file.delete(); throw java.io.IOException("signature mismatch")
        }
    }
}
