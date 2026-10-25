package com.noircipher.shade.ai

import android.content.Context
import android.util.Log
import java.io.Closeable

class NeuralPredictionEngine private constructor(
    private val vocabulary: NeuralVocabulary,
    private val predictor: NeuralPredictor
) : Closeable {

    @Volatile
    var enabled: Boolean = true

    private val contextSize = 16

    fun predictNext(
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

    fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>
    ): List<String> {
        if (!enabled || candidates.size <= 1) return candidates

        val neural = predictNext(
            beforeCursor = beforeCursor,
            typedPrefix = typedPrefix,
            limit = candidates.size + 5
        ).associate { it.first.lowercase() to it.second }

        if (neural.isEmpty()) return candidates

        return candidates
            .mapIndexed { index, candidate ->
                val baseRankScore = 1f / (index + 1)
                val neuralScore = neural[candidate.lowercase()] ?: 0f
                val prefixBonus = if (candidate.startsWith(typedPrefix, ignoreCase = true)) 0.05f else 0f
                val score = 0.66f * baseRankScore + 0.34f * neuralScore + prefixBonus
                candidate to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
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

        fun create(context: Context, languageTag: String = "en"): NeuralPredictionEngine? {
            val modelLanguage = normalizeLanguageTag(languageTag)
            return try {
                val vocabulary = NeuralVocabulary.load(context, modelLanguage) ?: return null
                val predictor = OnnxNeuralPredictor(context, vocabulary, languageTag = modelLanguage)
                NeuralPredictionEngine(vocabulary, predictor)
            } catch (t: Throwable) {
                Log.i(TAG, "Neural engine unavailable", t)
                null
            }
        }
    }
}
