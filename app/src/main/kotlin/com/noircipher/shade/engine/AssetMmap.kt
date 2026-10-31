package com.noircipher.shade.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads a model asset as a memory-mapped [MappedByteBuffer] instead of the
 * previous `assets.open(path).readBytes()` pattern.
 *
 * `readBytes()` allocates the entire model on the JVM heap — a 7 MB ONNX
 * model means ~7 MB of heap just to hold the bytes, on top of the in-memory
 * weights the interpreter itself allocates. Memory-mapping the asset file
 * descriptor instead lets the OS page the model in/out on demand and
 * eliminates the readBytes() heap allocation entirely.
 *
 * Requires the asset to be uncompressed in the APK — see `aaptOptions {
 * noCompress(...) }` in `app/build.gradle.kts`. If the asset IS compressed
 * (e.g. someone removes the noCompress line), `assets.openFd(path)` throws
 * `FileNotFoundException` because compressed assets can't be mmap'd — we
 * fall back to `readBytes()` in that case so the loader still works, just
 * with the old (worse) memory profile.
 */
internal object AssetMmap {

    private const val TAG = "AssetMmap"

    fun load(context: Context, path: String): ByteBuffer {
        return try {
            mmap(context, path)
        } catch (t: Throwable) {
            Log.w(TAG, "mmap failed for $path; falling back to readBytes", t)
            val bytes = context.assets.open(path).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .also { it.rewind() }
        }
    }

    private fun mmap(context: Context, path: String): MappedByteBuffer {
        val fd: AssetFileDescriptor = context.assets.openFd(path)
        return FileInputStream(fd.fileDescriptor).channel.use { channel ->
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }.also { fd.close() }
    }
}
