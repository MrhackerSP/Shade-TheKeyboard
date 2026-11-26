package com.shade.keyboard

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class EncryptedMapFile(ctx: Context, name: String, private val alias: String) {

    private val file = File(ctx.applicationContext.filesDir, name)
    private var loadFailed = false

    fun stamp(): Long = if (file.exists()) file.lastModified() * 31L + file.length() else 0L
    fun exists(): Boolean = file.exists()

    fun load(): MutableMap<String, Float>? {
        if (!file.exists()) return null
        return try {
            val plain = SecureStore.decrypt(alias, file.readText())
                ?: throw IllegalStateException("undecryptable")
            UserWordCodec.decode(plain)
        } catch (e: Exception) {
            loadFailed = true
            Log.w("EncryptedMapFile", "Cannot read ${file.name}; keeping for retry", e)
            null
        }
    }

    fun save(map: Map<String, Float>): Boolean {
        if (loadFailed) return false
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
