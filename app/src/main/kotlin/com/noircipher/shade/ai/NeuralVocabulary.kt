package com.noircipher.shade.ai

import android.content.Context
import android.util.Log
import com.noircipher.shade.GlideLMFeatures

class NeuralVocabulary private constructor(
    val idToToken: List<String>,
    private val tokenToId: Map<String, Int>
) {
    val size: Int get() = idToToken.size

    val bosId: Int = tokenToId["[BOS]"] ?: -1
    val eosId: Int = tokenToId["[EOS]"] ?: -1
    val unkId: Int = tokenToId["_OOV_"] ?: tokenToId["[UNK]"] ?: -1

    fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                val cleaned = current.toString().lowercase().trim('\'', '-', '_')
                if (cleaned.isNotEmpty()) tokens.add(cleaned)
                current.clear()
            }
        }

        for (ch in text) {
            when {
                ch.isLetterOrDigit() || ch.isCombiningMark() -> current.append(ch.lowercaseChar())
                ch == '\'' || ch == '\u2019' || ch == '-' || ch == '_' ->
                    current.append(if (ch == '\u2019') '\'' else ch)
                else -> flush()
            }
        }
        flush()
        return tokens
    }

    fun encode(tokens: List<String>, maxContext: Int): IntArray {
        val tail = tokens.takeLast(maxContext).filter { it.isNotEmpty() }
        // Empty context: only emit BOS when the vocabulary actually has one; -1 ids would
        // corrupt ONNX models that wrap negative indices.
        if (tail.isEmpty()) return if (bosId >= 0) intArrayOf(bosId) else IntArray(0)

        val hasBos = bosId >= 0
        val ids = IntArray(tail.size + if (hasBos) 1 else 0)
        var out = 0
        if (hasBos) ids[out++] = bosId
        for (token in tail) {
            val id = tokenToId[token] ?: tokenToId[token.lowercase()]
            if (id != null) {
                ids[out++] = id
            } else if (unkId >= 0) {
                ids[out++] = unkId
            }
        }
        return if (out == ids.size) ids else ids.copyOf(out)
    }

    private fun Char.isCombiningMark(): Boolean =
        category == CharCategory.NON_SPACING_MARK ||
            category == CharCategory.COMBINING_SPACING_MARK ||
            category == CharCategory.ENCLOSING_MARK

    fun topK(
        logits: FloatArray,
        limit: Int,
        prefix: String? = null
    ): List<Pair<String, Float>> {
        if (logits.isEmpty() || limit <= 0) return emptyList()

        val probabilities = softmax(logits)
        val wantedPrefix = prefix?.lowercase()

        val placeholderOffset = if (
            idToToken.firstOrNull().isNullOrEmpty() &&
            logits.size == idToToken.size - 1
        ) 1 else 0

        val heap = java.util.PriorityQueue<Pair<Float, Int>>(limit + 1) { a, b ->
            a.first.compareTo(b.first)
        }
        for (outputId in probabilities.indices) {
            val id = outputId + placeholderOffset
            val token = idToToken.getOrNull(id) ?: continue
            if (isSpecialToken(token)) continue
            if (wantedPrefix != null && !token.startsWith(wantedPrefix)) continue
            val p = probabilities[outputId]
            if (heap.size < limit) {
                heap.offer(p to id)
            } else if (p > (heap.peek()?.first ?: Float.NEGATIVE_INFINITY)) {
                heap.poll()
                heap.offer(p to id)
            }
        }

        val out = ArrayList<Pair<String, Float>>(heap.size)
        for ((_, id) in heap) {
            val token = idToToken.getOrNull(id) ?: continue
            out.add(token to probabilities[id - placeholderOffset])
        }
        out.sortByDescending { it.second }
        return out
    }

    /**
     * Turns next-word logits into a 0..1 "how well does this word fit here" value per word, for
     * glide typing. Plain log-probabilities (no temperature): a word with probability p gets
     * `1 + ln(p) / scale`, so p = 1 gives 1 and p = e^-scale (about 6e-6 for the default) gives 0.
     * Words less likely than that, and special tokens, are left out (no bonus, no penalty).
     */
    fun contextBonuses(logits: FloatArray, scale: Float = 12f): Map<String, Float> {
        if (logits.isEmpty() || scale <= 0f) return emptyMap()
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        for (v in logits) sum += kotlin.math.exp((v - max).toDouble())
        val logZ = max + kotlin.math.ln(sum).toFloat()

        val placeholderOffset = if (
            idToToken.firstOrNull().isNullOrEmpty() &&
            logits.size == idToToken.size - 1
        ) 1 else 0

        val out = HashMap<String, Float>(logits.size / 2)
        for (outputId in logits.indices) {
            val token = idToToken.getOrNull(outputId + placeholderOffset) ?: continue
            if (isSpecialToken(token)) continue
            val bonus = 1f + (logits[outputId] - logZ) / scale
            if (bonus > 0f) out[token] = bonus
        }
        return out
    }

    /**
     * Rich glide context: 4 derived features from the neural LM
     * (`mlm_score_d/p/r/s`). Each word gets:
     *
     *  - [score]   (the `_s` feature): 0..1 fit, same as [contextBonuses].
     *  - [diff]    (the `_d` feature): how much better this word is vs. the median candidate,
     *               0 = median, >0 = better, <0 = worse. Lets the decoder tell "10x more likely"
     *               from "1.1x more likely".
     *  - [position] (the `_p` feature): rank in the LM's top predictions (0 = most likely),
     *               normalised to 0..1 (0 = top, 1 = tail). A word at rank 0 is a strong signal.
     *  - [ratio]   (the `_r` feature): probability ratio vs. the top word (1 = top word,
     *               0.5 = half as likely). Distinguishes "both good" from "one clearly better".
     *
     * Words below the cutoff (e^-scale) and special tokens are left out.
     */
    fun contextFeatures(logits: FloatArray, scale: Float = 12f): Map<String, GlideLMFeatures> {
        if (logits.isEmpty() || scale <= 0f) return emptyMap()
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        for (v in logits) sum += kotlin.math.exp((v - max).toDouble())
        val logZ = max + kotlin.math.ln(sum).toFloat()

        val placeholderOffset = if (
            idToToken.firstOrNull().isNullOrEmpty() &&
            logits.size == idToToken.size - 1
        ) 1 else 0

        // First pass: collect (token, logProb, bonus) for all words above the cutoff.
        data class Raw(val token: String, val logProb: Float, val bonus: Float)
        val raws = ArrayList<Raw>(logits.size / 2)
        for (outputId in logits.indices) {
            val token = idToToken.getOrNull(outputId + placeholderOffset) ?: continue
            if (isSpecialToken(token)) continue
            val logProb = logits[outputId] - logZ  // log-probability (≤ 0)
            val bonus = 1f + logProb / scale
            if (bonus > 0f) raws.add(Raw(token, logProb, bonus))
        }
        if (raws.isEmpty()) return emptyMap()

        // Sort by logProb descending (most likely first) for rank/position.
        raws.sortByDescending { it.logProb }

        // The top word's probability (for ratio) and the median bonus (for diff).
        val topProb = kotlin.math.exp(raws[0].logProb.toDouble()).toFloat()
        val medianBonus = if (raws.size > 1) {
            // Median of bonuses — use the middle element of the sorted-by-bonus list.
            val byBonus = raws.sortedBy { it.bonus }
            byBonus[byBonus.size / 2].bonus
        } else raws[0].bonus

        val out = HashMap<String, GlideLMFeatures>(raws.size)
        for ((rank, r) in raws.withIndex()) {
            val prob = kotlin.math.exp(r.logProb.toDouble()).toFloat()
            val ratio = if (topProb > 0f) (prob / topProb).coerceIn(0f, 1f) else 0f
            val position = if (raws.size > 1) rank.toFloat() / (raws.size - 1) else 0f
            val diff = r.bonus - medianBonus
            out[r.token] = GlideLMFeatures(r.bonus, diff, position, ratio)
        }
        return out
    }

    private fun isSpecialToken(token: String): Boolean =
        token.isEmpty() ||
            token == "^" ||
            token == "_OOV_" ||
            (token.startsWith("[") && token.endsWith("]"))

    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v

        // Neural improvement #6 — temperature sharpening.
        // T < 1 sharpens the distribution: good predictions get higher
        // probability, bad ones get suppressed. The raw softmax over a 20K-
        // token vocab is very flat (top prediction often p=0.05), which makes
        // the ranker's confidence threshold unreliable. T=0.7 concentrates
        // probability mass on the top candidates so the ranker's
        // `neuralScore > 0.0025` threshold actually means something.
        val temperature = 0.7f

        val out = FloatArray(logits.size)
        var sum = 0f
        for (i in logits.indices) {
            val e = kotlin.math.exp(((logits[i] - max) / temperature).coerceAtMost(80f))
            out[i] = e
            sum += e
        }
        if (sum <= 0f) return FloatArray(logits.size)
        for (i in out.indices) out[i] /= sum
        return out
    }

    companion object {
        fun fromText(text: String, languageTag: String = "en"): NeuralVocabulary? {
            val byId = HashMap<Int, String>()
            var sequentialId = 0

            text.lineSequence().forEach { line ->
                if ('\t' in line) {
                    val parts = line.split('\t')
                    val id = parts.getOrNull(0)?.toIntOrNull() ?: sequentialId
                    val token = parts.getOrElse(2) { parts.last() }
                    byId[id] = token
                } else {
                    byId[sequentialId] = line
                }
                sequentialId++
            }

            if (byId.isEmpty()) return null

            val size = (byId.keys.maxOrNull() ?: -1) + 1
            val tokens = List(size) { byId[it] ?: "" }
            val tokenToId = HashMap<String, Int>()
            for ((id, token) in byId) {
                tokenToId[token] = id
                tokenToId.putIfAbsent(token.lowercase(), id)
            }
            return NeuralVocabulary(tokens, tokenToId)
        }

        fun load(
            context: Context,
            languageTag: String = "en"
        ): NeuralVocabulary? {
            val candidates = buildList {
                add("languages/${languageTag}/vocab_${languageTag}.tsv")
                add("languages/${languageTag}/vocab.tsv")
                add("neural/vocab_${languageTag}.tsv")
                add("neural/vocab_${languageTag}.txt")
                add("neural/vocab.tsv")
                add("neural/vocab.txt")
            }
            for (path in candidates) {
                try {
                    context.assets.open(path).bufferedReader().use { reader ->
                        val byId = HashMap<Int, String>()
                        var sequentialId = 0

                        reader.forEachLine { line ->
                            if ('\t' in line) {
                                val parts = line.split('\t')
                                val id = parts.getOrNull(0)?.toIntOrNull() ?: sequentialId
                                val token = parts.getOrElse(2) { parts.last() }
                                byId[id] = token
                            } else {
                                byId[sequentialId] = line
                            }
                            sequentialId++
                        }

                        if (byId.isEmpty()) return null

                        val size = (byId.keys.maxOrNull() ?: -1) + 1
                        val tokens = List(size) { byId[it] ?: "" }
                        val tokenToId = HashMap<String, Int>()
                        for ((id, token) in byId) {
                            tokenToId[token] = id
                            tokenToId.putIfAbsent(token.lowercase(), id)
                        }
                        return NeuralVocabulary(tokens, tokenToId)
                    }
                } catch (e: Exception) {
                    // Try the next format.
                    Log.w("NeuralVocabulary", "Failed to load neural vocabulary", e)
                }
            }
            return null
        }
    }
}
