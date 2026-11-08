package com.noircipher.shade

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SystemSpell {

    data class Result(
        val inDictionary: Boolean,
        val typo: Boolean,
        val recommended: Boolean,
        val suggestions: List<String>
    ) {
        val hasOpinion: Boolean get() = inDictionary || typo
    }

    private val NONE = Result(false, false, false, emptyList())
    private const val ATTRS = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY or
        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
        SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS

    private class Pending(val latch: CountDownLatch, val at: Long)

    private val handler: Handler by lazy {
        Handler(HandlerThread("system-spell").also { it.start() }.looper)
    }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val cache = object : LinkedHashMap<String, Result>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 500
    }
    private val pending = ConcurrentHashMap<String, Pending>()

    private var session: SpellCheckerSession? = null
    private var sessionLocale = ""
    private val failedAt = HashMap<String, Long>()
    private val queue = ArrayDeque<Pair<Int, String>>()
    private var seq = 0

    @Volatile
    var onResult: ((String) -> Unit)? = null

    private fun key(locale: String, word: String) = "$locale|$word"

    fun cached(locale: String, word: String): Result? =
        synchronized(cache) { cache[key(locale, word)] }?.takeIf { it !== NONE }

    private val debounceToken = Any()
    private var queued: Pair<String, Runnable>? = null

    fun request(context: Context, locale: String, word: String, debounceMs: Long = 0L) {
        if (word.length < 2 || word.length > 40 || !word.all { it.isLetter() || it == '\'' || it == '-' }) return
        val k = key(locale, word)
        if (synchronized(cache) { cache.containsKey(k) }) return
        val now = SystemClock.uptimeMillis()
        val p = pending[k]
        if (p != null && now - p.at < 1500L) return
        pending[k] = Pending(CountDownLatch(1), now)
        val app = context.applicationContext
        lateinit var task: Runnable
        task = Runnable {
            synchronized(debounceToken) { if (queued?.second === task) queued = null }
            runCatching { send(app, locale, word, k) }.onFailure { finish(k, null) }
        }
        if (debounceMs <= 0L) {
            handler.post(task)
            return
        }
        synchronized(debounceToken) {
            queued?.let { (oldKey, oldTask) ->
                handler.removeCallbacks(oldTask, debounceToken)
                pending.remove(oldKey)?.latch?.countDown()
            }
            queued = k to task
            handler.postAtTime(task, debounceToken, SystemClock.uptimeMillis() + debounceMs)
        }
    }

    fun flush() {
        val t = synchronized(debounceToken) { queued?.second } ?: return
        handler.removeCallbacks(t, debounceToken)
        handler.postAtFrontOfQueue(t)
    }

    fun await(locale: String, word: String, timeoutMs: Long): Result? {
        cached(locale, word)?.let { return it }
        val p = pending[key(locale, word)] ?: return null
        flush()
        runCatching { p.latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return cached(locale, word)
    }

    fun close() = handler.post {
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        while (queue.isNotEmpty()) finish(queue.removeFirst().second, null)
        // Release anything still waiting so await() callers don't sit out their full timeout.
        for (key in pending.keys.toList()) pending.remove(key)?.latch?.countDown()
    }

    fun currentName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < 31) return null
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        if (!tsm.isSpellCheckerEnabled) return null
        tsm.currentSpellCheckerInfo?.loadLabel(context.packageManager)?.toString()
    }.getOrNull()

    private fun finish(k: String, r: Result?) {
        synchronized(cache) { cache[k] = r ?: NONE }
        pending.remove(k)?.latch?.countDown()
        if (r != null && r.hasOpinion) {
            val word = k.substringAfter('|')
            main.post { onResult?.invoke(word) }
        }
    }

    @Suppress("DEPRECATION")
    private fun sessionFor(app: Context, locale: String): SpellCheckerSession? {
        session?.let { if (sessionLocale == locale) return it }
        runCatching { session?.close() }
        session = null
        sessionLocale = ""
        failedAt[locale]?.let { if (SystemClock.uptimeMillis() - it < 30_000L) return null }
        val tsm = app.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        val loc = Locale.forLanguageTag(locale)
        val s = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(loc)
                    .setShouldReferToSpellCheckerLanguageSettings(false)
                    .setSupportedAttributes(ATTRS)
                    .build()
                tsm.newSpellCheckerSession(params, { handler.post(it) }, listener)
            } else {
                tsm.newSpellCheckerSession(null, loc, listener, false)
            }
        }.getOrNull()
        if (s == null) failedAt[locale] = SystemClock.uptimeMillis() else failedAt.remove(locale)
        session = s
        sessionLocale = if (s != null) locale else ""
        return s
    }

    private fun send(app: Context, locale: String, word: String, k: String) {
        val s = sessionFor(app, locale)
        if (s == null) {
            finish(k, null)
            return
        }
        val id = ++seq
        queue.addLast(id to k)
        while (queue.size > 16) finish(queue.removeFirst().second, null)
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word, id, id)), 5) }.onFailure {
            queue.removeAll { it.first == id }
            finish(k, null)
        }
    }

    private fun deliver(info: SuggestionsInfo?) {
        if (queue.isEmpty()) return
        val idx = info?.let { i -> queue.indexOfFirst { it.first == i.sequence } }?.takeIf { it >= 0 } ?: 0
        repeat(idx) { finish(queue.removeFirst().second, null) }
        val k = queue.removeFirst().second
        if (info == null) {
            finish(k, null)
            return
        }
        val a = info.suggestionsAttributes
        val word = k.substringAfter('|')
        val list = (0 until maxOf(0, info.suggestionsCount))
            .mapNotNull { info.getSuggestionAt(it)?.trim() }
            .filter { it.isNotEmpty() && it != word }
            .distinct()
        finish(
            k, Result(
                inDictionary = a and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0,
                typo = a and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                recommended = a and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
                suggestions = list
            )
        )
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            runCatching { results?.forEach { deliver(it) } }
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            runCatching {
                if (results.isNullOrEmpty()) return deliver(null)
                for (s in results) {
                    if (s == null || s.suggestionsCount <= 0) {
                        deliver(null)
                        continue
                    }
                    val i = (0 until s.suggestionsCount).firstOrNull { s.getOffsetAt(it) == 0 } ?: 0
                    deliver(s.getSuggestionsInfoAt(i))
                }
            }
        }
    }
}
