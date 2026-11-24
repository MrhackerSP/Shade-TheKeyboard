package com.shade.keyboard.gif

import android.content.Context
import com.shade.keyboard.ShadeLog
import android.content.SharedPreferences
import com.shade.keyboard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * State machine behind the GIF panel. The panel (see [GifPanel]) is laid out like the
 * emoji panel: a search box above a vertically scrolling grid. While the query box owns
 * input the service forwards keystrokes into [type]/[backspace]/[submit]; the ✕ button
 * and the Enter key come back through the [GifPanel.Callbacks] here.
 *
 *  - Nothing is requested from the GIF provider until the person accepts the one-time notice (`gif_consent`).
 *  - Opening shows trending GIFs; typing live-searches after a short pause, Enter searches immediately.
 *  - Only what is typed into the search box is sent, never the text of the message.
 */
class GifController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val onClose: () -> Unit,
    private val onSend: (GifItem, String) -> Unit
) : GifPanel.Callbacks {

    val loader = GifImageLoader(context, scope)
    val panel = GifPanel(context, loader, this)

    // The client is rebuilt whenever the effective API key changes (build-time key or the
    // runtime key pasted in the panel's setup card), so a saved key works immediately.
    private var clientInstance: GifClient? = null
    private var clientKey: String? = null

    private val client: GifClient?
        get() {
            val key = GifConfig.apiKey
            if (key.isBlank()) return null
            if (clientInstance == null || clientKey != key) {
                clientInstance = GifClient(key, customerId())
                clientKey = key
            }
            return clientInstance
        }

    private var activeQuery = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private var generation = 0
    private var job: Job? = null
    private var liveSearchJob: Job? = null

    var isOpen = false
        private set

    private val consented get() = prefs.getBoolean(KEY_CONSENT, false)

    fun open() {
        isOpen = true
        activeQuery = ""
        panel.reset()
        when {
            client == null -> panel.showSetup()
            !consented -> panel.showConsent()
            else -> loadFirst("")
        }
    }

    fun close() {
        isOpen = false
        generation++
        job?.cancel()
        liveSearchJob?.cancel()
        loading = false
        panel.reset()
        loader.clear()
    }

    // ---- keystrokes forwarded by the service -----------------------------------------------

    fun type(text: String) {
        if (!isOpen) return
        // While the API-key setup card is open, keystrokes feed the key field instead of the query.
        if (panel.isCapturingKey) { panel.appendKeyInput(text); return }
        if (panel.isSearching) panel.typeIntoSearch(text)
    }

    fun backspace() {
        if (!isOpen) return
        if (panel.isCapturingKey) { panel.backspaceKeyInput(); return }
        if (panel.isSearching) panel.searchBackspace()
    }

    fun submit() {
        if (!isOpen) return
        if (panel.isCapturingKey) { panel.submitKeyInput(); return }
        if (panel.isSearching) panel.submitSearch()
    }

    // ---- GifPanel callbacks ---------------------------------------------------------------------

    override fun onClose() = onClose.invoke()

    override fun onPick(item: GifItem) = onSend(item, activeQuery)

    override fun onNearEnd() {
        if (isOpen && hasNext && !loading) load(activeQuery, page + 1, replace = false)
    }

    override fun onConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
        loadFirst("")
    }

    override fun onApiKeySaved(key: String) {
        prefs.edit().putString(GifConfig.KEY_PREF, key.trim()).apply()
        GifConfig.applyPrefs(prefs)
        panel.showStatus(context.getString(R.string.gif_key_saved))
        if (consented) loadFirst("") else panel.showConsent()
    }

    override fun onQueryEdited(query: String) {
        if (!isOpen || !panel.isSearching) return
        // Live search: only the settled query is loaded (each keystroke resets the pause).
        liveSearchJob?.cancel()
        liveSearchJob = scope.launch {
            delay(LIVE_SEARCH_DELAY_MS)
            if (!isOpen || !panel.isSearching) return@launch
            activeQuery = query.trim()
            loadFirst(activeQuery)
        }
    }

    override fun onSearchSubmitted(query: String) {
        if (!isOpen) return
        liveSearchJob?.cancel()
        activeQuery = query.trim()
        loadFirst(activeQuery)
    }

    override fun onQueryCleared() {
        if (!isOpen) return
        liveSearchJob?.cancel()
        activeQuery = ""
        if (client != null && consented) loadFirst("")
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
            panel.clearGrid()
            panel.showStatus(context.getString(R.string.gif_loading))
        }
        job = scope.launch {
            val result: GifPage? = try {
                withContext(Dispatchers.IO) { if (q.isEmpty()) c.trending(p) else c.search(q, p) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                ShadeLog.w("GifController", "gif search failed", t, once = true)
                null
            }
            if (gen != generation || !isOpen) return@launch
            loading = false
            if (result == null) {
                if (replace) panel.showStatus(context.getString(R.string.gif_error))
                return@launch
            }
            page = result.page
            hasNext = result.hasNext
            panel.showItems(result.items, append = !replace)
            if (replace && result.items.isEmpty()) panel.showStatus(context.getString(R.string.gif_no_results))
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
        private const val MAX_SEND_BYTES = 12 * 1024 * 1024
        private const val LIVE_SEARCH_DELAY_MS = 400L
    }
}
