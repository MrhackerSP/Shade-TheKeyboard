package com.noircipher.shade

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Single warn channel for the IME's fault-tolerance guards.
 *
 * A keyboard must never crash on an optional feature, so the codebase is
 * deliberately full of guarded failures. Before this object existed each
 * guard chose its own behaviour: some logged, some were completely silent
 * (`catch (_: Exception)`), and there was no single place to audit them.
 * Every swallowed failure now reports through [w]:
 *
 *  - `once = true` collapses repeated identical (tag, message) pairs to a
 *    single logcat line, so guards on per-keystroke or per-frame paths can
 *    stay visible without spamming;
 *  - a null [t] logs a plain warning (the throwable added nothing);
 *  - logging never rethrows — the guard's fallback still decides the
 *    outcome, exactly as before.
 */
internal object ShadeLog {

    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun w(tag: String, msg: String, t: Throwable? = null, once: Boolean = false) {
        if (once && !seen.add("$tag|$msg")) return
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }
}
