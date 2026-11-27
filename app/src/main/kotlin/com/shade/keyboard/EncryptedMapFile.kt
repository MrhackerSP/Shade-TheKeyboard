package com.shade.keyboard

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A small word -> score map persisted as ONE Keystore-encrypted file.
 * Fails closed: if encryption is unavailable, [save] returns false and nothing is
 * written. A file that cannot be read is kept for retry (transient Keystore errors)
 * or quarantined as `<name>.bad` only when the data/key is permanently unusable.
 */
class EncryptedMapFile(ctx: Context, name: String, private val alias: String) {

    private val file = File(ctx.applicationContext.filesDir, name)

    /** Set when an existing file could not be read/decrypted this session. */
    private var loadFailed = false

    fun stamp(): Long = if (file.exists()) file.lastModified() * 31L + file.length() else 0L

    fun exists(): Boolean = file.exists()

    fun load(): MutableMap<String, Float>? {
        if (!file.exists()) {
            loadFailed = false
            return null
        }
        val stored = try {
            file.readText()
        } catch (e: Exception) {
            loadFailed = true
            Log.w("EncryptedMapFile", "Cannot read ${file.name}; keeping for retry", e)
            return null
        }
        return when (val out = SecureStore.decryptTyped(alias, stored)) {
            is SecureStore.DecryptOutcome.Plain -> {
                loadFailed = false // healthy read re-arms saving
                try {
                    UserWordCodec.decode(out.text)
                } catch (e: Exception) {
                    Log.w("EncryptedMapFile", "Decoded payload unusable; quarantining", e)
                    quarantine()
                    null
                }
            }
            SecureStore.DecryptOutcome.Corrupt -> {
                // Data or key permanently unusable: quarantine and start fresh,
                // otherwise every future save would be refused for nothing.
                quarantine()
                null
            }
            SecureStore.DecryptOutcome.Transient -> {
                loadFailed = true
                Log.w("EncryptedMapFile", "Transient keystore error; keeping file for retry", null)
                null
            }
        }
    }

    private fun quarantine() {
        loadFailed = false
        try {
            file.renameTo(File(file.parentFile, file.name + ".bad"))
        } catch (e: Exception) {
            Log.w("EncryptedMapFile", "Failed to quarantine corrupt map", e)
        }
    }

    /** Returns false (and writes nothing) if the data could not be encrypted or written. */
    fun save(map: Map<String, Float>): Boolean {
        if (loadFailed) {
            return false // never clobber data we couldn't read
        }
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

/**
 * Runs [action] on a background thread [delayMs] after the LAST [request], so a
 * burst of keystrokes causes one disk write. [shutdown] flushes immediately.
 */
class DebouncedSaver(private val delayMs: Long, private val action: () -> Unit) {

    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "shade-save").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    private fun runAction() {
        try { action() } catch (t: Throwable) { Log.w("DebouncedSaver", "save failed", t) }
    }

    @Synchronized
    fun request() {
        if (exec.isShutdown) return
        pending?.cancel(false)
        pending = exec.schedule(Runnable { runAction() }, delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun shutdown() {
        if (exec.isShutdown) return
        pending?.cancel(false)
        exec.execute(Runnable { runAction() })
        exec.shutdown()
    }
}
