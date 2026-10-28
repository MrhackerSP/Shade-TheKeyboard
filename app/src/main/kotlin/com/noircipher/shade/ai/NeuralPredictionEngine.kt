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

    private val contextSize = 24
    private var lastContextIds: IntArray? = null
    private var lastLogits: FloatArray? = null

    override fun predictNext(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> = predictNextWithScores(beforeCursor, typedPrefix, limit)

    @Synchronized
    private fun logitsFor(beforeCursor: String): FloatArray? {
        return try {
            val tokens = vocabulary.tokenize(beforeCursor)
            val contextIds = vocabulary.encode(tokens, contextSize)
            val cachedIds = lastContextIds
            val cachedLogits = lastLogits
            if (cachedIds != null && cachedIds.contentEquals(contextIds) && cachedLogits != null) {
                cachedLogits
            } else {
                val logits = predictor.predictLogits(contextIds)
                lastContextIds = contextIds
                lastLogits = logits
                logits
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Neural prediction failed", t)
            null
        }
    }

    @Synchronized
    private fun predictNextWithScores(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> {
        if (!enabled || limit <= 0) return emptyList()
        val logits = logitsFor(beforeCursor) ?: return emptyList()
        return vocabulary.topK(logits, limit, typedPrefix)
    }

    override fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>
    ): List<String> {
        if (!enabled || candidates.size <= 1) return candidates

        val neuralPredictions = predictNextWithScores(
            beforeCursor = beforeCursor,
            typedPrefix = typedPrefix,
            limit = candidates.size + 12
        )

        if (neuralPredictions.isEmpty()) return candidates

        val neural = neuralPredictions.associate { it.first.lowercase() to it.second }
        val merged = (neuralPredictions.map { it.first } + candidates)
            .distinctBy { it.lowercase() }

        return merged
            .mapIndexed { index, candidate ->
                val baseRankScore = 1f / (index + 1)
                val neuralScore = neural[candidate.lowercase()] ?: 0f
                val prefixBonus = when {
                    candidate.equals(typedPrefix, ignoreCase = true) -> 0.10f
                    candidate.startsWith(typedPrefix, ignoreCase = true) -> 0.06f
                    else -> 0f
                }

                // When the neural model is confident, trust it more; otherwise keep
                // dictionary order stronger. Full-vocab probabilities are usually small,
                // so use a small absolute confidence threshold.
                val strongModel = vocabulary.size >= 15000
                val modelWeight = if (strongModel) 0.74f else 0.58f
                val baseWeight = if (strongModel) 0.18f else 0.32f
                val confidence = if (neuralScore > 0.0025f) 1f else 0.45f
                val score = confidence * (modelWeight * neuralScore + baseWeight * baseRankScore) + prefixBonus
                candidate to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    @Synchronized
    override fun close() {
        lastContextIds = null
        lastLogits = null
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
                // Priority: reconstructed NWP for English, then standard ONNX.
                if (modelLanguage == "en") {
                    try {
                        val nwpVocabulary = NeuralVocabulary.load(
                            context = context,
                            languageTag = modelLanguage,
                            includeNwp = true
                        ) ?: error("NWP vocabulary unavailable")
                        if (nwpVocabulary.size != 9498) error("Unexpected NWP vocabulary size")
                        val nwpPredictor = OnnxNwpPredictor(context, nwpVocabulary)
                        return NeuralPredictionEngine(nwpVocabulary, nwpPredictor)
                    } catch (_: Throwable) {
                        // Fall through to the standard ONNX model.
                    }
                }

                val vocabulary = NeuralVocabulary.load(
                    context = context,
                    languageTag = modelLanguage,
                    includeNwp = false
                ) ?: return null
                val predictor = OnnxNeuralPredictor(context, vocabulary, languageTag = modelLanguage)
                NeuralPredictionEngine(vocabulary, predictor)
            } catch (t: Throwable) {
                Log.i(TAG, "Neural engine unavailable", t)
                null
            }
        }
    }
}
