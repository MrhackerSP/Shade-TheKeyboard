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

    private var client: KlipyClient? = null
    private var clientKey: String = ""

    private var query = ""
    private var activeQuery = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private var generation = 0
    private var job: Job? = null

    var isOpen = false
        private set

    /** True while the bar is asking for the runtime KLIPY key (no key baked in or saved). */
    private var setupMode = false

    private val consented get() = prefs.getBoolean(KEY_CONSENT, false)

    /** Build-time key wins; otherwise the key the person pasted into the panel. */
    private fun currentKey(): String = KlipyConfig.effectiveKey(prefs.getString(KEY_RUNTIME_API, null))

    /** Lazily builds the client from the current key (re-resolved after a runtime key is saved). */
    private fun ensureClient(): KlipyClient? {
        val key = currentKey()
        if (key.isBlank()) return null
        var c = client
        if (c == null || clientKey != key) {
            c = KlipyClient(key, customerId())
            client = c
            clientKey = key
        }
        return c
    }

    fun open() {
        isOpen = true
        query = ""
        activeQuery = ""
        bar.setQuery("")
        when {
            ensureClient() == null -> {
                setupMode = true
                bar.showSetup()
            }
            !consented -> bar.showConsent()
            else -> loadFirst("")
        }
    }

    fun close() {
        isOpen = false
        setupMode = false
        generation++
        job?.cancel()
        loading = false
        bar.clearThumbs()
        loader.clear()
    }

    // ---- keystrokes forwarded by the service -----------------------------------------------

    fun type(text: String) {
        if (!isOpen) return
        // While asking for the key, everything typed becomes the key.
        if (!setupMode && (ensureClient() == null || !consented)) return
        query = (query + text).take(MAX_QUERY)
        bar.setQuery(query)
    }

    fun backspace() {
        if (!isOpen || query.isEmpty()) return
        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
        bar.setQuery(query)
    }

    fun submit() {
        if (!isOpen) return
        if (setupMode) {
            val key = query.trim()
            query = ""
            activeQuery = ""
            bar.setQuery("")
            if (key.isNotEmpty()) onApiKeySave(key)
            return
        }
        if (ensureClient() == null || !consented) return
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

    override fun onApiKeySave(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(KEY_RUNTIME_API, trimmed).apply()
        if (ensureClient() != null) {
            setupMode = false
            if (!consented) bar.showConsent() else loadFirst("")
        } else {
            bar.showSetup(error = true)
        }
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
        val c = ensureClient() ?: return
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
        val c = ensureClient() ?: return
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
        private const val KEY_RUNTIME_API = "gif_api_key"
        private const val SEND_DIR = "gif_send"
        private const val MAX_QUERY = 60
        private const val MAX_SEND_BYTES = 12 * 1024 * 1024
    }
}
