package com.noircipher.shade.ai

import android.content.Context

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
                tokens.add(current.toString().lowercase())
                current.clear()
            }
        }

        for (ch in text) {
            when {
                ch.isLetterOrDigit() || ch.isCombiningMark() -> current.append(ch.lowercaseChar())
                ch == '\'' || ch == '-' || ch == '_' -> current.append(ch)
                else -> flush()
            }
        }
        flush()
        return tokens
    }

    fun encode(tokens: List<String>, maxContext: Int): IntArray {
        val tail = tokens.takeLast(maxContext).filter { it.isNotEmpty() }
        if (tail.isEmpty()) return intArrayOf(bosId)

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

    private fun isSpecialToken(token: String): Boolean =
        token.isEmpty() ||
            token == "^" ||
            token == "_OOV_" ||
            (token.startsWith("[") && token.endsWith("]"))

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
            languageTag: String = "en",
            includeNwp: Boolean = true
        ): NeuralVocabulary? {
            val candidates = buildList {
                if (includeNwp && languageTag == "en") {
                    add("languages/en/vocab_nwp_en.tsv")
                }
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
                } catch (_: Exception) {
                    // Try the next format.
                }
            }
            return null
        }
    }
}
