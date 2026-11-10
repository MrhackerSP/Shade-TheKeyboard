package com.noircipher.shade.gif

import android.content.Context
import android.content.SharedPreferences
import com.noircipher.shade.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * State machine behind the GIF bar. The query is typed on the real keyboard: while the bar is open
 * the service forwards keystrokes to [type]/[backspace]/[submit] instead of the text field.
 *
 *  - Nothing is requested from KLIPY until the person accepts the one-time notice (`gif_consent`).
 *  - Opening shows trending GIFs; Enter on the keyboard searches the typed words.
 *  - The text field's content is never sent, only what is typed into the bar.
 */
class GifController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val onClose: () -> Unit,
    private val onSend: (GifItem, String) -> Unit
) : GifBar.Callbacks {

    val loader = GifImageLoader(context, scope)
    val bar = GifBar(context, loader, this)

    private val client: KlipyClient? =
        if (KlipyConfig.isConfigured) KlipyClient(KlipyConfig.apiKey, customerId()) else null

    private var query = ""
    private var activeQuery = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private var generation = 0
    private var job: Job? = null

    var isOpen = false
        private set

    private val consented get() = prefs.getBoolean(KEY_CONSENT, false)

    fun open() {
        isOpen = true
        query = ""
        activeQuery = ""
        bar.setQuery("")
        when {
            client == null -> bar.showStatus(context.getString(R.string.gif_unavailable))
            !consented -> bar.showConsent()
            else -> loadFirst("")
        }
    }

    fun close() {
        isOpen = false
        generation++
        job?.cancel()
        loading = false
        bar.clearThumbs()
        loader.clear()
    }

    // ---- keystrokes forwarded by the service -----------------------------------------------

    fun type(text: String) {
        if (!isOpen || client == null || !consented) return
        query = (query + text).take(MAX_QUERY)
        bar.setQuery(query)
    }

    fun backspace() {
        if (!isOpen || query.isEmpty()) return
        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
        bar.setQuery(query)
    }

    fun submit() {
        if (!isOpen || client == null || !consented) return
        activeQuery = query.trim()
        loadFirst(activeQuery)
    }

    // ---- GifBar callbacks ---------------------------------------------------------------------

    override fun onClose() = onClose.invoke()

    override fun onPick(item: GifItem) = onSend(item, activeQuery)

    override fun onNearEnd() {
        if (isOpen && hasNext && !loading) load(activeQuery, page + 1, replace = false)
    }

    override fun onConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
        loadFirst("")
    }

    // ---- sending ------------------------------------------------------------------------------

    /** Downloads the full-size GIF to the cache so it can be handed over through the FileProvider. */
    suspend fun downloadForSend(item: GifItem): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SEND_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val ext = if (item.send.format == "webp") "webp" else "gif"
        val safe = item.slug.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48).ifEmpty { "gif" }
        val file = File(dir, "$safe.$ext")
        if (HttpFetch.downloadTo(item.send.url, file, MAX_SEND_BYTES)) file else null
    }

    fun registerShare(item: GifItem, searched: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) { c.registerShare(item.slug, searched.ifBlank { null }) }
    }

    // ---- loading ------------------------------------------------------------------------------

    private fun loadFirst(q: String) {
        generation++
        page = 0
        hasNext = false
        load(q, 1, replace = true)
    }

    private fun load(q: String, p: Int, replace: Boolean) {
        val c = client ?: return
        job?.cancel()
        loading = true
        val gen = generation
        if (replace) {
            bar.clearThumbs()
            bar.showStatus(context.getString(R.string.gif_loading))
        }
        job = scope.launch {
            val result: GifPage? = try {
                withContext(Dispatchers.IO) { if (q.isEmpty()) c.trending(p) else c.search(q, p) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != generation || !isOpen) return@launch
            loading = false
            if (result == null) {
                if (replace) bar.showStatus(context.getString(R.string.gif_error))
                return@launch
            }
            page = result.page
            hasNext = result.hasNext
            bar.showItems(result.items, append = !replace)
            if (replace && result.items.isEmpty()) bar.showStatus(context.getString(R.string.gif_no_results))
        }
    }

    /** Random per-install id; not derived from the device or account. */
    private fun customerId(): String {
        prefs.getString(KEY_CUSTOMER, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CUSTOMER, id).apply()
        return id
    }

    companion object {
        private const val KEY_CONSENT = "gif_consent"
        private const val KEY_CUSTOMER = "gif_customer_id"
        private const val SEND_DIR = "gif_send"
        private const val MAX_QUERY = 60
        private const val MAX_SEND_BYTES = 12 * 1024 * 1024
    }
}
