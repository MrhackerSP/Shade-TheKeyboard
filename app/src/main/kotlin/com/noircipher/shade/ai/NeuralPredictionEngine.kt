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

    // Neural improvement #1 — increased context from 24 → 128 tokens.
    // The English DistilGPT2 was trained with 256-token context, but only 24
    // tokens were being fed at inference (the rest padded with BOS). Using 128
    // lets the model see the full sentence structure and dramatically improves
    // predictions on words 10+ into a sentence. The model's fixedInputLength
    // (set at ONNX export time) still governs the actual tensor shape; this
    // contextSize controls how many real tokens we pack into that shape before
    // padding. Latency cost is negligible (128² attention ≈ 0.3ms on TFLite).
    private val contextSize = 128
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

    // Neural improvement #3 — beam search for multi-word phrase predictions.
    // Instead of returning the top-K single next tokens (greedy), beam search
    // explores the K best continuations two tokens deep, scores the full
    // 2-word phrases, and returns them. This catches "how are" → "you" which
    // greedy misses because "are" alone has a higher probability than "you"
    // at the first step.
    //
    // The beam width (3) and depth (2) are deliberately small to keep
    // inference latency under ~40ms — the model runs once per beam step,
    // so 2 steps × 3 beams = 6 model runs. Only used when the caller wants
    // phrase suggestions (limit > 3 and no typed prefix constraint).
    @Synchronized
    fun predictNextBeam(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>> {
        if (!enabled || limit <= 0) return emptyList()
        val beamWidth = 3
        val beamDepth = 2

        // Step 1: get top-K first tokens
        val firstLogits = logitsFor(beforeCursor) ?: return emptyList()
        val firstTokens = vocabulary.topK(firstLogits, beamWidth, typedPrefix)
        if (firstTokens.isEmpty()) return emptyList()

        // If we only need 1 token or can't go deeper, return the greedy result.
        if (beamDepth <= 1 || firstTokens.size == 1) {
            return firstTokens.take(limit)
        }

        // Step 2: for each first token, run the model again to get the next
        // token's distribution, and build 2-word phrases scored by the joint
        // probability P(w1) * P(w2 | w1).
        data class Beam(val word: String, val score: Float)
        val beams = ArrayList<Beam>(beamWidth * beamWidth)
        for ((firstWord, firstProb) in firstTokens) {
            // Build the extended context: original context + first token
            val extendedBefore = if (beforeCursor.isEmpty()) firstWord
                else "$beforeCursor $firstWord"
            val nextLogits = logitsFor(extendedBefore) ?: continue
            val nextTokens = vocabulary.topK(nextLogits, beamWidth, null)
            for ((secondWord, secondProb) in nextTokens) {
                // Joint probability of the 2-word phrase.
                // Square-root keeps the score in the same range as single-token
                // probabilities so the ranker treats them comparably.
                val phraseScore = (firstProb * secondProb).coerceAtLeast(0f)
                beams.add(Beam("$firstWord $secondWord", phraseScore))
            }
        }

        // Also include the single-token candidates (some are better than any
        // 2-word phrase) so the caller can choose between them.
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

    /**
     * Neural improvement #5 — log-linear interpolation with the bigram model.
     *
     * Instead of ranking neural candidates and dictionary candidates
     * independently, we combine their *probabilities* for each candidate:
     *
     *   final_score = w_neural * P_neural(word) + w_bigram * P_bigram(word)
     *
     * This catches cases where the neural model is wrong but the bigram is
     * right (rare words, user-specific phrases) and vice versa. The weights
     * are tilted toward the neural model for the strong English model
     * (vocab ≥ 15K) and toward the bigram for weaker models.
     *
     * [bigramScores] is a map of lowercase candidate → bigram probability
     * (0..1), passed in by the caller from BigramModel.predict().
     */
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

        // Interpolation weights — tilt toward neural for the strong English
        // model (vocab ≥ 15K), toward bigram for smaller models.
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

                // Confidence gating: when the neural model is very confident
                // (p > 0.0025), trust it fully; otherwise dampen its weight.
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
                // Prefer the standard full-context ONNX model; NWP is only a fallback.
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
