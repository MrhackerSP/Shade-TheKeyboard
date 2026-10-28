package com.noircipher.shade

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports Shade's locally-learned data in a training-friendly format.
 *
 * Two modes:
 *  - exportToUri(): user-initiated, writes to a chosen file via SAF.
 *  - autoExport(): periodic, writes into filesDir/training_exports/.
 *    Runs only when contribute_data is on and at least 24h have passed
 *    since the last export.
 *
 * Only aggregated statistics are exported:
 *  - user_words: word → frequency (from SharedPreferences)
 *  - ngram.json: bigram/trigram counts (from BigramModel)
 *
 * No raw typing history, no sentence reconstruction, no clipboard.
 */
object TrainingExport {

    private const val SCHEMA_VERSION = 1
    private const val MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L  // 24 hours
    private const val MAX_KEPT_EXPORTS = 7
    private const val EXPORT_DIR = "training_exports"
    private const val PREF_LAST_EXPORT = "last_training_export"

    /** User-initiated export via SAF. Returns true on success. */
    fun exportToUri(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            writeZip(ctx, out)
        } != null
    } catch (_: Exception) {
        false
    }

    /**
     * Called from the service on onFinishInput(). Silently exports if the
     * consent flag is on and enough time has passed. Does file I/O on the
     * calling thread — callers should invoke from a background coroutine.
     *
     * Returns the exported file, or null if no export happened.
     */
    fun autoExport(ctx: Context): File? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean("contribute_data", true)) return null

        val now = System.currentTimeMillis()
        val last = prefs.getLong(PREF_LAST_EXPORT, 0L)
        if (now - last < MIN_INTERVAL_MS) return null

        // Nothing useful to export yet.
        if (!hasTrainingData(ctx)) return null

        val dir = File(ctx.filesDir, EXPORT_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val out = File(dir, "shade-training-$stamp.zip")

        return try {
            FileOutputStream(out).use { writeZip(ctx, it) }
            prefs.edit().putLong(PREF_LAST_EXPORT, now).apply()
            pruneOldExports(dir)
            out
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    /** Deletes all pending auto-exports. Called when the user opts out. */
    fun clearAutoExports(ctx: Context) {
        val dir = File(ctx.filesDir, EXPORT_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    fun hasTrainingData(ctx: Context): Boolean {
        val userPrefs = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
        if (userPrefs.all.size >= 5) return true
        val ngram = File(ctx.filesDir, "ngram.json")
        return ngram.exists() && ngram.length() > 64
    }

    private fun writeZip(ctx: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            writeMeta(ctx, zip)
            writeUserWords(ctx, zip)
            writeNgram(ctx, zip)
        }
    }

    private fun writeMeta(ctx: Context, zip: ZipOutputStream) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val meta = JSONObject().apply {
            put("schema", SCHEMA_VERSION)
            put("exported_at", System.currentTimeMillis())
            put("locale", prefs.getString("locale", "en") ?: "en")
            put("layout", prefs.getString("layout", "qwerty") ?: "qwerty")
            put("device_id", deviceHash(ctx))
            put("app_version", BuildConfig.VERSION_NAME)
        }
        zip.putNextEntry(ZipEntry("meta.json"))
        OutputStreamWriter(zip, Charsets.UTF_8).apply {
            write(meta.toString(2))
            flush()
        }
        zip.closeEntry()
    }

    private fun writeUserWords(ctx: Context, zip: ZipOutputStream) {
        val prefs = ctx.getSharedPreferences("user_words", Context.MODE_PRIVATE)
        val entries = prefs.all
            .mapNotNull { (k, v) ->
                val freq = when (v) {
                    is Float -> v
                    is Int -> v.toFloat()
                    else -> null
                } ?: return@mapNotNull null
                if (!k.all { WordDictionary.isWordChar(it) }) return@mapNotNull null
                if (k.length < 2 || k.length > 32) return@mapNotNull null
                k to freq
            }
            .sortedByDescending { it.second }

        zip.putNextEntry(ZipEntry("user_words.txt"))
        OutputStreamWriter(zip, Charsets.UTF_8).use { w ->
            for ((word, freq) in entries) {
                w.write(word)
                w.write("\t")
                w.write(freq.toString())
                w.write("\n")
            }
        }
        zip.closeEntry()
    }

    private fun writeNgram(ctx: Context, zip: ZipOutputStream) {
        val f = File(ctx.filesDir, "ngram.json")
        if (!f.exists()) return
        if (f.length() > 5_000_000L) return
        zip.putNextEntry(ZipEntry("ngram.json"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun pruneOldExports(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_KEPT_EXPORTS) return
        for (i in MAX_KEPT_EXPORTS until files.size) {
            files[i].delete()
        }
    }

    private fun deviceHash(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("export_meta", Context.MODE_PRIVATE)
        var id = prefs.getString("install_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("install_id", id).apply()
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(id.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
