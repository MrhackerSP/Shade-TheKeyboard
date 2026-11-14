package com.noircipher.shade

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports Shade's locally-learned data to a file on this device. NOTHING is uploaded:
 * the app has no INTERNET permission. The user shares the file manually if they choose to.
 *
 * Two modes:
 *  - exportToUri(): user-initiated, writes to a chosen file via SAF.
 *  - autoExport(): periodic, writes into filesDir/training_exports/.
 *    Runs only when contribute_data is explicitly enabled and at least 24h have passed
 *    since the last export.
 *
 * Only aggregated statistics are exported:
 *  - user_words: word → frequency (decrypted from the encrypted store)
 *  - ngram.json: bigram/trigram counts (decrypted from BigramModel's store)
 *
 * No raw typing history and no clipboard. Note that trigram counts can still hint at
 * phrases, so treat an export as personal data. No device ID is included.
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
        if (!prefs.getBoolean("contribute_data", false)) return null

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
        if ((WordDictionary.openUserStore(ctx).load()?.size ?: 0) >= 5) return true
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
        val entries = WordDictionary.openUserStore(ctx).load().orEmpty()
            .entries
            .filter { UserWordCodec.isValidWord(it.key) }
            .sortedByDescending { it.value }

        zip.putNextEntry(ZipEntry("user_words.txt"))
        val w = OutputStreamWriter(zip, Charsets.UTF_8)
        for ((word, freq) in entries) {
            w.write(word)
            w.write("\t")
            w.write(freq.toString())
            w.write("\n")
        }
        w.flush() // don't close: that would close the zip stream
        zip.closeEntry()
    }

    private fun writeNgram(ctx: Context, zip: ZipOutputStream) {
        val json = BigramModel.readPersistedJson(ctx) ?: return
        if (json.length > 5_000_000) return
        zip.putNextEntry(ZipEntry("ngram.json"))
        zip.write(json.toByteArray(Charsets.UTF_8))
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
}
