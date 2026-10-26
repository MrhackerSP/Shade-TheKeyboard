package com.noircipher.shade.ai

import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.LanguageModelEngine
import java.io.Closeable

class NeuralPredictionEngine private constructor(
    private val vocabulary: NeuralVocabulary,
    private val predictor: NeuralPredictor
) : LanguageModelEngine {

    @Volatile
    override var enabled: Boolean = true

    private val contextSize = 16

    override fun predictNext(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> {
        if (!enabled || limit <= 0) return emptyList()

        return try {
            val tokens = vocabulary.tokenize(beforeCursor)
            val contextIds = vocabulary.encode(tokens, contextSize)
            val logits = predictor.predictLogits(contextIds)
            vocabulary.topK(logits, limit, typedPrefix)
        } catch (t: Throwable) {
            Log.w(TAG, "Neural prediction failed", t)
            emptyList()
        }
    }

    override fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>
    ): List<String> {
        if (!enabled) return candidates

        val neural = predictNext(
            beforeCursor = beforeCursor,
            typedPrefix = typedPrefix,
            limit = candidates.size + 8
        ).associate { it.first.lowercase() to it.second }

        if (neural.isEmpty()) return candidates
        val maxP = neural.values.maxOrNull() ?: return candidates
        if (maxP <= 0f) return candidates

        // Strong neural completions the dictionary did not offer (at most two).
        val seen = candidates.map { it.lowercase() }.toHashSet()
        val extras = neural.entries
            .filter { it.key !in seen && (it.value >= 0.05f || it.value >= 0.5f * maxP) }
            .sortedByDescending { it.value }
            .take(2)
            .map { matchCase(it.key, typedPrefix) }

        val all = candidates + extras
        return all
            .mapIndexed { index, candidate ->
                val baseRankScore = 1f / (index + 1)
                // Normalise so the neural signal is comparable to the rank signal;
                // raw probabilities (~0.05) were too small to ever change the order.
                val neuralScore = (neural[candidate.lowercase()] ?: 0f) / maxP
                val prefixBonus = if (candidate.startsWith(typedPrefix, ignoreCase = true)) 0.05f else 0f
                candidate to (0.5f * baseRankScore + 0.5f * neuralScore + prefixBonus)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> word.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    override fun close() {
        predictor.close()
    }

    companion object {
        private const val TAG = "NeuralPredictionEngine"

        private fun normalizeLanguageTag(languageTag: String): String {
            val lower = languageTag.lowercase()
            return when {
                lower.startsWith("hg") -> "hg"
                lower.startsWith("hinglish") -> "hg"
                lower.startsWith("hi") -> "hi"
                else -> "en"
            }
        }

        /** Untrained/placeholder models return identical logits for every context. */
        private fun respondsToInput(vocabulary: NeuralVocabulary, predictor: NeuralPredictor): Boolean {
            return try {
                val n = 16
                val hi = vocabulary.size - 1
                val a = predictor.predictLogits(IntArray(n) { 0 })
                val b = predictor.predictLogits(IntArray(n) { i -> if (i >= n - 3) minOf(100 * (i - n + 4), hi) else 0 })
                val c = predictor.predictLogits(IntArray(n) { i -> if (i >= n - 3) minOf(900 + 300 * (i - n + 4), hi) else 0 })
                var diff = 0f
                for (i in 0 until minOf(a.size, b.size, c.size)) {
                    diff = maxOf(diff, kotlin.math.abs(a[i] - b[i]), kotlin.math.abs(b[i] - c[i]))
                }
                diff > 0.01f
            } catch (t: Throwable) {
                true
            }
        }

        fun create(context: Context, languageTag: String = "en"): NeuralPredictionEngine? {
            val modelLanguage = normalizeLanguageTag(languageTag)
            return try {
                val vocabulary = NeuralVocabulary.load(context, modelLanguage) ?: return null
                val predictor = OnnxNeuralPredictor(context, vocabulary, languageTag = modelLanguage)
                if (!respondsToInput(vocabulary, predictor)) {
                    Log.w(TAG, "Neural model for $modelLanguage ignores its input; disabling it")
                    predictor.close()
                    return null
                }
                NeuralPredictionEngine(vocabulary, predictor)
            } catch (t: Throwable) {
                Log.i(TAG, "Neural engine unavailable", t)
                null
            }
        }
    }
}
