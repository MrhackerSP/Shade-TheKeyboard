package com.noircipher.shade

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A small word -> score map persisted as ONE Keystore-encrypted file.
 *
 * Fails closed: if encryption is unavailable, [save] returns false and nothing is
 * written. If the file can't be decrypted (e.g. the Keystore key was lost) it is
 * quarantined as `<name>.bad` and [load] returns null instead of crashing.
 */
class EncryptedMapFile(ctx: Context, name: String, private val alias: String) {

    private val file = File(ctx.applicationContext.filesDir, name)

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
            Log.w("EncryptedMapFile", "Cannot read ${file.name}; quarantining", e)
            try { file.renameTo(File(file.parentFile, file.name + ".bad")) } catch (_: Exception) {}
            null
        }
    }

    /** Returns false (and writes nothing) if the data could not be encrypted or written. */
    fun save(map: Map<String, Float>): Boolean {
        val encrypted = SecureStore.encrypt(alias, UserWordCodec.encode(map)) ?: return false
        return try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encrypted)
            if (!tmp.renameTo(file)) {
                file.writeText(encrypted)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete() {
        try { file.delete() } catch (_: Exception) {}
        try { File(file.parentFile, file.name + ".bad").delete() } catch (_: Exception) {}
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

    /** Final flush on the background thread; never blocks the caller. */
    @Synchronized
    fun shutdown() {
        if (exec.isShutdown) return
        pending?.cancel(false)
        exec.execute(Runnable { runAction() })
        exec.shutdown()
    }
}
