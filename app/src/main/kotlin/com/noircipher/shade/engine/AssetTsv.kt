package com.noircipher.shade.engine

import android.content.Context
import android.util.Log

/**
 * One fault-tolerance contract for every line-based asset table in the app.
 *
 * The IME must never crash because an optional data file is missing, corrupt
 * or malformed — the feature that consumes the table disables itself instead.
 * Before this object existed, every loader re-implemented that guard as its
 * own try/catch (some silent, some logging, some catching Exception and some
 * Throwable); they all funnel through here now:
 *  - failures are caught as Throwable — an Error subclass must disable one
 *    feature, never kill the whole IME process;
 *  - every failure is logged through Log.w (visible, never silent, never
 *    fatal);
 *  - [onError] is returned, so the caller degrades exactly as it did before.
 */
internal object AssetTsv {

    fun <R> use(
        context: Context,
        path: String,
        tag: String,
        onError: R,
        failureMessage: String = "asset table failed to load: $path; feature disabled",
        block: (Sequence<String>) -> R
    ): R = try {
        context.assets.open(path).bufferedReader().useLines(block)
    } catch (t: Throwable) {
        Log.w(tag, failureMessage, t)
        onError
    }
}
