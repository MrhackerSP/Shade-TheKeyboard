package com.noircipher.shade.gif

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Minimal image loader for the GIF picker: downloads a preview, caches the encoded bytes in memory
 * and decodes it (animated on API 28+, first frame below). Not a general purpose loader; there is
 * one cache and it is dropped whenever the picker closes.
 */
class GifImageLoader(private val context: Context, private val scope: CoroutineScope) {

    private class Binding(val url: String, val job: Job)

    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val gate = Semaphore(MAX_PARALLEL)

    fun load(url: String, into: ImageView) {
        (into.tag as? Binding)?.job?.cancel()
        into.setImageDrawable(null)
        val job = scope.launch {
            val drawable: Drawable? = try {
                withContext(Dispatchers.IO) {
                    val bytes = cache.get(url) ?: gate.withPermit {
                        HttpFetch.getBytes(url, MAX_PREVIEW_BYTES).also { cache.put(url, it) }
                    }
                    decode(bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val current = into.tag as? Binding
            if (drawable != null && current?.url == url) {
                into.setImageDrawable(drawable)
                if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
            }
        }
        into.tag = Binding(url, job)
    }

    /** Stops animations and cancels a view's pending load (call when a thumbnail is removed). */
    fun release(view: ImageView) {
        (view.tag as? Binding)?.job?.cancel()
        val d = view.drawable
        if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) d.stop()
        view.setImageDrawable(null)
        view.tag = null
    }

    fun clear() {
        cache.evictAll()
    }

    private fun decode(bytes: ByteArray): Drawable? {
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            } catch (_: Exception) {
                null
            }
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    companion object {
        private const val CACHE_BYTES = 6 * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 3 * 1024 * 1024
        private const val MAX_PARALLEL = 4
    }
}
