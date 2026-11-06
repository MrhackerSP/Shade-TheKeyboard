package com.noircipher.shade.ai

import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.CrossSentenceContext
import com.noircipher.shade.engine.LanguageModelEngine
import java.io.Closeable
import kotlin.math.sqrt

class NeuralPredictionEngine private constructor(
    private val vocabulary: NeuralVocabulary,
    private val predictor: NeuralPredictor
) : LanguageModelEngine {

    @Volatile
    override var enabled: Boolean = true

    // Keep the model input bounded to what the ONNX export can actually accept.
    // The final tensor length is set by the model itself; this value is the number
    // of real tokens we fold into the fixed shape before padding.
    private val contextSize = 128
    private var lastContextIds: IntArray? = null
    private var lastLogits: FloatArray? = null

    private fun trimmedContext(beforeCursor: String): String {
        val raw = beforeCursor.trim()
        if (raw.isEmpty()) return raw
        val afterBoundary = CrossSentenceContext.truncateAtSentenceBoundary(raw)
        return if (afterBoundary.isNotEmpty()) afterBoundary else raw
    }

    override fun predictNext(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> = predictNextWithScores(beforeCursor, typedPrefix, limit)

    @Synchronized
    private fun logitsFor(beforeCursor: String): FloatArray? {
        return try {
            val contextText = trimmedContext(beforeCursor)
            val tokens = vocabulary.tokenize(contextText)
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

    @Synchronized
    fun predictNextBeam(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> {
        if (!enabled || limit <= 0) return emptyList()
        val beamWidth = 3
        val beamDepth = 2

        val firstLogits = logitsFor(beforeCursor) ?: return emptyList()
        val firstTokens = vocabulary.topK(firstLogits, beamWidth, typedPrefix)
        if (firstTokens.isEmpty()) return emptyList()

        if (beamDepth <= 1 || firstTokens.size == 1) {
            return firstTokens.take(limit)
        }

        data class Beam(val word: String, val score: Float)
        val beams = ArrayList<Beam>(beamWidth * beamWidth)
        for ((firstWord, firstProb) in firstTokens) {
            val extendedBefore = if (beforeCursor.isEmpty()) firstWord else "${trimmedContext(beforeCursor)} $firstWord".trim()
            val nextLogits = logitsFor(extendedBefore) ?: continue
            val nextTokens = vocabulary.topK(nextLogits, beamWidth, null)
            for ((secondWord, secondProb) in nextTokens) {
                val phraseScore = sqrt((firstProb * secondProb).coerceAtLeast(0f))
                beams.add(Beam("$firstWord $secondWord", phraseScore))
            }
        }

        val result = ArrayList<Pair<String, Float>>(firstTokens.size + beams.size)
        for ((word, prob) in firstTokens) result.add(word to prob)
        for ((phrase, score) in beams) result.add(phrase to score)

        return result.sortedByDescending { it.second }.take(limit)
    }

    override fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>
    ): List<String> = rerankPrefix(beforeCursor, typedPrefix, candidates, emptyMap())

    @Synchronized
    fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>,
        bigramScores: Map<String, Float>
    ): List<String> {
        if (!enabled || candidates.size <= 1) return candidates

        val neuralPredictions = predictNextWithScores(
            beforeCursor = beforeCursor,
            typedPrefix = typedPrefix,
            limit = candidates.size + 12
        )

        if (neuralPredictions.isEmpty() && bigramScores.isEmpty()) return candidates

        val neural = neuralPredictions.associate { it.first.lowercase() to it.second }
        val merged = (neuralPredictions.map { it.first } + candidates)
            .distinctBy { it.lowercase() }

        val strongModel = vocabulary.size >= 15000
        val wNeural = if (strongModel) 0.62f else 0.42f
        val wBigram = if (strongModel) 0.22f else 0.38f
        val wBase = if (strongModel) 0.16f else 0.20f

        return merged
            .mapIndexed { index, candidate ->
                val baseRankScore = 1f / (index + 1)
                val neuralScore = neural[candidate.lowercase()] ?: 0f
                val bigramScore = bigramScores[candidate.lowercase()] ?: 0f
                val prefixBonus = when {
                    candidate.equals(typedPrefix, ignoreCase = true) -> 0.10f
                    candidate.startsWith(typedPrefix, ignoreCase = true) -> 0.06f
                    else -> 0f
                }

                val confidence = if (neuralScore > 0.0025f) 1f else 0.45f
                val score = confidence * (wNeural * neuralScore + wBigram * bigramScore + wBase * baseRankScore) + prefixBonus
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
                val vocabulary = NeuralVocabulary.load(
                    context = context,
                    languageTag = modelLanguage,
                    includeNwp = false
                ) ?: error("Standard vocabulary unavailable")
                val predictor = OnnxNeuralPredictor(context, vocabulary, languageTag = modelLanguage)
                NeuralPredictionEngine(vocabulary, predictor)
            } catch (_: Throwable) {
                try {
                    val nwpVocabulary = NeuralVocabulary.load(
                        context = context,
                        languageTag = modelLanguage,
                        includeNwp = true
                    ) ?: return null
                    if (nwpVocabulary.size != 9498) return null
                    val nwpPredictor = OnnxNwpPredictor(context, nwpVocabulary)
                    NeuralPredictionEngine(nwpVocabulary, nwpPredictor)
                } catch (t: Throwable) {
                    Log.i(TAG, "Neural engine unavailable", t)
                    null
                }
            }
        }
    }
}
