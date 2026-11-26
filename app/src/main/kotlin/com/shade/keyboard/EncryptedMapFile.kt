package com.shade.keyboard

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A small word -> score map persisted as ONE Keystore-encrypted file.
 *
 * Fails closed: if encryption is unavailable, [save] returns false and nothing is
 * written. If the file can't be decrypted (e.g. the Keystore key was lost) it is
 * quarantined as `<name>.bad` and [load] returns null instead of crashing.
 */
class EncryptedMapFile(ctx: Context, name: String, private val alias: String) {

    private val file = File(ctx.applicationContext.filesDir, name)

    /** Set when an existing file could not be read/decrypted this session. */
    private var loadFailed = false

    /** Changes whenever the file changes; 0 when it doesn't exist. */
    fun stamp(): Long = if (file.exists()) file.lastModified() * 31L + file.length() else 0L

    fun exists(): Boolean = file.exists()

    fun load(): MutableMap<String, Float>? {
        if (!file.exists()) return null
        return try {
            val plain = SecureStore.decrypt(alias, file.readText())
                ?: throw IllegalStateException("undecryptable")
            UserWordCodec.decode(plain)
        } catch (e: Exception) {
            // Keep the file intact — a Keystore hiccup or disk error is often
            // transient. Retry on next launch; learning is paused until then.
            loadFailed = true
            Log.w("EncryptedMapFile", "Cannot read ${file.name}; keeping for retry", e)
            null
        }
    }

    /** Returns false (and writes nothing) if the data could not be encrypted or written. */
    fun save(map: Map<String, Float>): Boolean {
        if (loadFailed) return false // never clobber data we couldn't read
        val encrypted = SecureStore.encrypt(alias, UserWordCodec.encode(map)) ?: return false
        return try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(encrypted.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            true
        } catch (t: Exception) {
            ShadeLog.w("EncryptedMapFile", "encrypted map save failed", t, once = true)
            false
        }
    }

    fun delete() {
        loadFailed = false
        try { file.delete() } catch (e: Exception) { Log.w("EncryptedMapFile", "Failed to delete encrypted map file", e) }
        try { File(file.parentFile, file.name + ".bad").delete() } catch (e: Exception) { Log.w("EncryptedMapFile", "Failed to delete encrypted map .bad file", e) }
    }
}
