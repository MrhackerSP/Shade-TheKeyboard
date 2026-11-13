package com.noircipher.shade.ai

import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.CandidateRanker
import com.noircipher.shade.engine.CrossSentenceContext
import com.noircipher.shade.engine.LanguageModelEngine

class NeuralPredictionEngine private constructor(
    private val vocabulary: NeuralVocabulary,
    private val predictor: NeuralPredictor
) : LanguageModelEngine {

    @Volatile
    override var enabled: Boolean = true

    // The exported model's input shape is authoritative. This cap only bounds
    // the real tokens prepared before the predictor pads/truncates to that shape.
    private val contextSize = 128
    private var lastContextIds: IntArray? = null
    private var lastLogits: FloatArray? = null

    private fun trimmedContext(beforeCursor: String): String =
        CrossSentenceContext.truncateAtSentenceBoundary(beforeCursor)

    override fun predictNext(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> = predictNextWithScores(beforeCursor, typedPrefix, limit)

    @Synchronized
    private fun logitsFor(beforeCursor: String): FloatArray? {
        return try {
            val tokens = vocabulary.tokenize(trimmedContext(beforeCursor))
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

    /**
     * How well each vocabulary word fits as the word after [beforeCursor], 0..1 (see
     * [NeuralVocabulary.contextBonuses]). Glide typing uses it to pick between swipes that look alike.
     * The model run is shared with the suggestion bar: for the same context it is already cached.
     */
    @Synchronized
    fun contextBonuses(beforeCursor: String): Map<String, Float> {
        if (!enabled) return emptyMap()
        val logits = logitsFor(beforeCursor) ?: return emptyMap()
        return vocabulary.contextBonuses(logits)
    }

    /**
     * Rich glide context: 4 derived features (score, diff, position, ratio) from the neural
     * LM (`mlm_score_d/p/r/s`). See [NeuralVocabulary.contextFeatures].
     */
    @Synchronized
    fun contextFeatures(beforeCursor: String): Map<String, com.noircipher.shade.GlideLMFeatures> {
        if (!enabled) return emptyMap()
        val logits = logitsFor(beforeCursor) ?: return emptyMap()
        return vocabulary.contextFeatures(logits)
    }

    @Synchronized
    fun predictNextBeam(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> {
        if (!enabled || limit <= 0) return emptyList()
        val beamWidth = 3
        val firstTokens = vocabulary.topK(logitsFor(beforeCursor) ?: return emptyList(), beamWidth, typedPrefix)
        if (firstTokens.isEmpty()) return emptyList()
        if (firstTokens.size == 1) return firstTokens.take(limit)

        data class Beam(val phrase: String, val score: Float)
        val beams = ArrayList<Beam>(beamWidth * beamWidth)
        val cleanContext = trimmedContext(beforeCursor)
        for ((firstWord, firstProb) in firstTokens) {
            val extendedContext = listOf(cleanContext, firstWord).filter { it.isNotBlank() }.joinToString(" ")
            val nextLogits = logitsFor(extendedContext) ?: continue
            for ((secondWord, secondProb) in vocabulary.topK(nextLogits, beamWidth, null)) {
                // Geometric mean keeps two-token phrase scores on the same rough
                // scale as a single-token probability; this is a ranking heuristic.
                val phraseScore = kotlin.math.sqrt((firstProb * secondProb).coerceAtLeast(0f))
                beams.add(Beam("$firstWord $secondWord", phraseScore))
            }
        }

        return (firstTokens + beams.map { it.phrase to it.score })
            .sortedByDescending { it.second }
            .take(limit)
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

        // Include strong neural candidates in the pool before ranking, then use
        // normalized scores rather than source insertion order to choose winners.
        val pool = (neuralPredictions.map { it.first } + candidates)
            .distinctBy { it.lowercase() }
        return CandidateRanker.rankContextual(
            candidates = pool,
            neuralScores = neuralPredictions.associate { it.first.lowercase() to it.second },
            bigramScores = bigramScores,
            typedPrefix = typedPrefix
        )
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
                lower.startsWith("hg") || lower.startsWith("hinglish") -> "hg"
                lower.startsWith("hi") -> "hi"
                else -> "en"
            }
        }

        fun create(context: Context, languageTag: String = "en"): NeuralPredictionEngine? {
            val modelLanguage = normalizeLanguageTag(languageTag)
            return try {
                val vocabulary = NeuralVocabulary.load(context, modelLanguage)
                    ?: error("Standard vocabulary unavailable")
                NeuralPredictionEngine(vocabulary, OnnxNeuralPredictor(context, vocabulary, languageTag = modelLanguage))
            } catch (t: Throwable) {
                Log.i(TAG, "Neural engine unavailable", t)
                null
            }
        }
    }
}
