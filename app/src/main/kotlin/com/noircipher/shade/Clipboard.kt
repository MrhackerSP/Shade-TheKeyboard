package com.noircipher.shade

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Clipboard history, stored only on this device. Pinned entries survive "clear". */
class ClipboardStore(private val ctx: Context) {
    data class Clip(
        val text: String = "",
        val image: String? = null,
        val mime: String = "text/plain",
        val pinned: Boolean = false,
        val time: Long = 0L
    ) {
        val id: String get() = image ?: text
        val isImage: Boolean get() = image != null
    }

    private fun Clip.imageBytes(): ByteArray? {
        val value = image ?: return null
        return if (value.startsWith("id:")) {
            readImageFile(value.removePrefix("id:"))
        } else {
            try { Base64.decode(value, Base64.NO_WRAP) } catch (_: Exception) { null }
        }
    }

    private val prefs = ctx.getSharedPreferences("clip_history", Context.MODE_PRIVATE)
    private val keyAlias = "shade_clipboard_key"
    private val imageDir = File(ctx.cacheDir, "clipboard_images").apply { mkdirs() }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(stored: String): String? {
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun load(): MutableList<Clip> {
        val out = ArrayList<Clip>()
        val now = System.currentTimeMillis()
        try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = decrypt(o.getString("t")) ?: o.getString("t")
                val image = o.optString("img").takeIf { it.isNotEmpty() }?.let { decrypt(it) ?: it }
                val clip = Clip(
                    text = text,
                    image = image,
                    mime = o.optString("mime", if (image != null) "image/*" else "text/plain"),
                    pinned = o.optBoolean("p"),
                    time = o.optLong("ts")
                )
                if (clip.pinned || now - clip.time <= UNPINNED_TTL_MS) out.add(clip)
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    private fun save(list: List<Clip>) {
        val arr = JSONArray()
        list.forEach {
            val text = encrypt(it.text) ?: return@forEach
            val image = it.image?.let { enc -> encrypt(enc) ?: return@forEach }
            arr.put(
                JSONObject()
                    .put("t", text)
                    .put("img", image ?: "")
                    .put("mime", it.mime)
                    .put("p", it.pinned)
                    .put("ts", it.time)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
        pruneImageFiles(list)
    }

    @Synchronized
    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val list = load()
        val old = list.firstOrNull { it.id == clean }
        list.removeAll { it.id == clean }
        list.add(0, Clip(text = clean, pinned = old?.pinned ?: false, time = System.currentTimeMillis()))
        var unpinned = 0
        save(list.filter { it.pinned || ++unpinned <= MAX_UNPINNED })
    }

    @Synchronized
    fun addImage(bytes: ByteArray, mime: String) {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return
        val imageId = sha256(bytes)
        writeImageFile(bytes, imageId) ?: return
        val ref = "id:$imageId"
        val list = load()
        val old = list.firstOrNull { it.image == ref }
        list.removeAll { it.image == ref }
        list.add(0, Clip(image = ref, mime = mime, pinned = old?.pinned ?: false, time = System.currentTimeMillis()))
        var unpinned = 0
        save(list.filter { it.pinned || ++unpinned <= MAX_UNPINNED })
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun writeImageFile(bytes: ByteArray, id: String): String? {
        return try {
            val stored = encrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)) ?: return null
            File(imageDir, "$id.enc").writeText(stored)
            id
        } catch (_: Exception) {
            null
        }
    }

    private fun pruneImageFiles(list: List<Clip>) {
        val refs = list.mapNotNull {
            it.image?.takeIf { ref -> ref.startsWith("id:") }?.removePrefix("id:")
        }.toSet()
        imageDir.listFiles()
            ?.filter { it.extension == "enc" && it.nameWithoutExtension !in refs }
            ?.forEach { it.delete() }
    }

    private fun readImageFile(id: String): ByteArray? {
        return try {
            val stored = File(imageDir, "$id.enc").readText()
            val decoded = decrypt(stored) ?: return null
            Base64.decode(decoded, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun cleanupExpired() {
        save(load())
    }

    @Synchronized
    fun togglePin(id: String) = save(load().map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    @Synchronized
    fun remove(id: String) = save(load().filter { it.id != id })
    @Synchronized
    fun clearUnpinned() = save(load().filter { it.pinned })
    @Synchronized
    fun clearAll() {
        prefs.edit().remove("items").apply()
        pruneImageFiles(emptyList())
    }

    fun writeTempImage(bytes: ByteArray, mime: String): File? {
        return try {
            val ext = if (mime.contains("png")) "png" else "jpg"
            val file = File(imageDir, "clip_${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)
            file
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val UNPINNED_TTL_MS = 60L * 60L * 1000L
        private const val MAX_UNPINNED = 12
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    }
}

class ClipboardPanel(
    context: Context,
    private val store: ClipboardStore,
    private val onPaste: (ClipboardStore.Clip) -> Unit,
    private val onBack: () -> Unit,
    private val onDelete: () -> Unit
) : LinearLayout(context) {

    private var clips: List<ClipboardStore.Clip> = emptyList()
    private var theme: KbTheme = Themes.LIGHT
    private val thumbScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val thumbCache = LruCache<String, android.graphics.Bitmap>(32)
    private val list = ListView(context)
    private val emptyView = TextView(context)
    private val abc = TextView(context)
    private val clear = TextView(context)
    private val del = ImageView(context)

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        thumbScope.cancel()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = clips.size
        override fun getItem(position: Int) = clips[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val clip = clips[position]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(6f), dp(2f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(theme.key)
                }
                setOnClickListener { onPaste(clip) }
            }

            if (clip.isImage) {
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(theme.bgKey)
                }
                thumb.tag = clip.id
                val cached = thumbCache.get(clip.id)
                if (cached != null) {
                    thumb.setImageBitmap(cached)
                } else {
                    thumbScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            clip.imageBytes()?.let { bytes -> decodeThumbnail(bytes) }
                        }
                        if (bmp != null) thumbCache.put(clip.id, bmp)
                        if (thumb.tag == clip.id) thumb.setImageBitmap(bmp)
                    }
                }
                row.addView(thumb, LayoutParams(dp(44f), dp(44f)))
                row.addView(TextView(context).apply {
                    text = context.getString(R.string.clip_image)
                    textSize = 14f
                    setTextColor(theme.text)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10f), 0, 0, 0)
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            } else {
                val tv = TextView(context).apply {
                    text = clip.text
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 14f
                    setTextColor(theme.text)
                }
                row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }

            row.addView(TextView(context).apply {
                text = if (clip.pinned) "★" else "☆"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (clip.pinned) theme.accent else theme.hint)
                setOnClickListener { store.togglePin(clip.id); refresh() }
            }, LayoutParams(dp(40f), dp(40f)))

            row.addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(theme.hint)
                setOnClickListener { store.remove(clip.id); refresh() }
            }, LayoutParams(dp(40f), dp(40f)))
            return row
        }
    }

    init {
        orientation = VERTICAL
        list.adapter = adapter
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(6f)
        list.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        list.clipToPadding = false
        emptyView.text = context.getString(R.string.clip_empty)
        emptyView.gravity = Gravity.CENTER
        list.emptyView = emptyView
        val frame = FrameLayout(context)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        abc.text = context.getString(R.string.abc)
        abc.gravity = Gravity.CENTER
        abc.textSize = 14f
        abc.setOnClickListener { onBack() }
        bar.addView(abc, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        clear.text = context.getString(R.string.clip_clear)
        clear.gravity = Gravity.CENTER
        clear.textSize = 14f
        clear.setOnClickListener { store.clearUnpinned(); refresh() }
        bar.addView(clear, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        del.setImageResource(R.drawable.ic_backspace)
        del.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        del.setOnClickListener { onDelete() }
        bar.addView(del, LayoutParams(dp(56f), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
    }

    fun refresh() {
        clips = store.load()
        adapter.notifyDataSetChanged()
    }

    fun setTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.bg)
        emptyView.setTextColor(t.hint)
        abc.setTextColor(t.text)
        clear.setTextColor(t.text)
        del.setColorFilter(t.text)
        adapter.notifyDataSetChanged()
    }
}
private fun decodeThumbnail(bytes: ByteArray): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) > 256 || bounds.outHeight / (sample * 2) > 256) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) {
        null
    }
}
