package com.noircipher.shade.ai

import android.content.Context

class NeuralVocabulary private constructor(
    val idToToken: List<String>,
    private val tokenToId: Map<String, Int>
) {
    val size: Int get() = idToToken.size

    val bosId: Int = tokenToId["[BOS]"] ?: 1
    val eosId: Int = tokenToId["[EOS]"] ?: 2
    val unkId: Int = tokenToId["[UNK]"] ?: tokenToId["_OOV_"] ?: 3

    fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens.add(current.toString().lowercase())
                current.clear()
            }
        }

        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> current.append(ch.lowercaseChar())
                ch == ''' || ch == '-' || ch == '_' -> current.append(ch)
                else -> flush()
            }
        }
        flush()
        return tokens
    }

    fun encode(tokens: List<String>, maxContext: Int): IntArray {
        val tail = tokens.takeLast(maxContext)
        val ids = IntArray(maxContext) { bosId }
        val start = maxContext - tail.size

        for (i in tail.indices) {
            val token = tail[i]
            ids[start + i] = tokenToId[token]
                ?: tokenToId[token.lowercase()]
                ?: unkId
        }

        return ids
    }

    fun topK(
        logits: FloatArray,
        limit: Int,
        prefix: String? = null
    ): List<Pair<String, Float>> {
        if (!logits.indices.any()) return emptyList()
        if (limit <= 0) return emptyList()

        val probabilities = softmax(logits)
        val wantedPrefix = prefix?.lowercase()
        val candidates = ArrayList<Triple<Int, String, Float>>()

        // Some ONNX models omit the empty placeholder ID 0. When that happens,
        // shift model output indices by one so class 0 maps to vocabulary ID 1.
        val placeholderOffset = if (
            idToToken.firstOrNull().isNullOrEmpty() &&
            logits.size == idToToken.size - 1
        ) 1 else 0

        for (outputId in probabilities.indices) {
            val id = outputId + placeholderOffset
            val token = idToToken.getOrNull(id) ?: continue
            if (token.isEmpty()) continue
            if (token.startsWith("[") && token.endsWith("]")) continue
            if (wantedPrefix != null && !token.startsWith(wantedPrefix)) continue
            candidates.add(Triple(id, token, probabilities[outputId]))
        }

        return candidates
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.second to it.third }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v

        val out = FloatArray(logits.size)
        var sum = 0f
        for (i in logits.indices) {
            val e = kotlin.math.exp((logits[i] - max).coerceAtMost(80f))
            out[i] = e
            sum += e
        }
        if (sum <= 0f) return FloatArray(logits.size)
        for (i in out.indices) out[i] /= sum
        return out
    }

    companion object {
        fun load(context: Context, languageTag: String = "en"): NeuralVocabulary? {
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
                            if ('	' in line) {
                                val parts = line.split('	')
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
                } catch (_: Exception) {
                    // Try the next format.
                }
            }
            return null
        }
    }
}
