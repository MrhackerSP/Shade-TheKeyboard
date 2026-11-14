package com.noircipher.shade.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter

/**
 * Single construction point for every bundled TFLite interpreter: memory-maps
 * the asset through [AssetMmap] and applies the threading policy, so
 * interpreter-construction semantics live in exactly one place instead of
 * being re-implemented in each scorer.
 *
 * Two guards, preserving the call-site conventions that existed before this
 * loader existed:
 *  - [load] throws on any failure — for callers that run model-specific
 *    contract checks and cleanup around construction (ConceptScorer,
 *    NeuralSpellChecker) or that construct scorers inside their own
 *    try/catch { null } (ShadeService).
 *  - [loadOrNull] catches Throwable — NOT Exception: a failed TFLite native
 *    library load (e.g. on the JVM in unit tests) surfaces as
 *    UnsatisfiedLinkError, which is an Error and would escape an Exception
 *    guard and kill the whole IME process. Passing a [tag] routes the
 *    failure through the standard warn channel; without a tag the failure
 *    stays silent, matching the historical silent guards.
 *
 * [numThreads] == null constructs `Interpreter(buffer)` with the runtime
 * default — byte-identical to the historical call sites that passed no
 * Options at all.
 */
internal object TfliteModelLoader {

    fun load(context: Context, modelPath: String, numThreads: Int? = null): Interpreter {
        val buffer = AssetMmap.load(context, modelPath)
        return if (numThreads == null) {
            Interpreter(buffer)
        } else {
            Interpreter(buffer, Interpreter.Options().apply { this.numThreads = numThreads })
        }
    }

    fun loadOrNull(
        context: Context,
        modelPath: String,
        numThreads: Int? = null,
        tag: String? = null,
        failureMessage: String = "tflite model failed to load: $modelPath; feature disabled"
    ): Interpreter? = try {
        load(context, modelPath, numThreads)
    } catch (t: Throwable) {
        if (tag != null) Log.w(tag, failureMessage, t)
        null
    }
}
