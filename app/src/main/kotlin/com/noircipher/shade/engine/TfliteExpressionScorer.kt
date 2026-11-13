package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Transformer expression scorer over the Gboard model bundled in
 * `assets/emoji_expression/transformer_expression.tflite`.
 *
 * The model takes INT32 token ids `[1,10]` produced by its own tokenizer
 * vocabulary — recovered by reverse engineering the `token.csym` symbol
 * container shipped next to it (16,467 tokens; `<S>`=1, `</S>`=0, `<UNK>`=2,
 * word ids remapped into model id space in `token_vocab.tsv`). Input scheme,
 * verified against the model: `[<S>, w1..wN]`, right-padded with 0, where the
 * `[1,4522]` output indexes the 4,522 expression queries decoded from
 * `transformer_expression.query_mapping.pb` (emoji per query, weight-ordered,
 * in `expression_query_emoji.tsv`).
 *
 * Legacy STRING-input paths removed: the bundled transformer takes INT32 token
 * ids and that is the only model this build ships.
 */
class TfliteExpressionScorer(
    context: Context,
    modelPath: String = "emoji_expression/transformer_expression.tflite",
    tokenVocabPath: String = "emoji_expression/token_vocab.tsv",
    queryEmojiPath: String = "emoji_expression/expression_query_emoji.tsv"
) : ExpressionScorer {

    /** Tokenizer vocabulary: model id -> token (csym remap already applied). */
    private val tokenToId: Map<String, Int> = try {
        val map = HashMap<String, Int>()
        context.assets.open(tokenVocabPath).bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val token = parts.getOrNull(1) ?: continue
                map.putIfAbsent(token, id)
            }
        }
        map
    } catch (_: Exception) {
        emptyMap()
    }

    private val bosId: Int = tokenToId["<S>"] ?: 1
    private val unkId: Int = tokenToId["<UNK>"] ?: 2

    /** Expression query id -> weight-ordered emoji candidates. */
    private val queryEmojis: Map<Int, List<String>> = try {
        val map = HashMap<Int, List<String>>()
        context.assets.open(queryEmojiPath).bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val emojis = parts.getOrNull(1)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
                if (emojis.isNotEmpty()) map[id] = emojis
            }
        }
        map
    } catch (_: Exception) {
        emptyMap()
    }

    private val interpreter: Interpreter? = try {
        val modelBuffer = AssetMmap.load(context, modelPath)
        Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = 2 })
    } catch (_: Exception) {
        null
    }

    private val queryOutputIndex: Int = interpreter?.let { itp ->
        var found = -1
        val wanted = queryEmojis.keys.maxOrNull() ?: -1
        for (i in 0 until itp.outputTensorCount) {
            val shape = itp.getOutputTensor(i).shape()
            val last = shape.lastOrNull() ?: 0
            if (wanted >= 0 && shape.size >= 2 && last >= wanted && last <= wanted + 64) {
                found = i
                break
            }
        }
        found
    } ?: -1

    override val isAvailable: Boolean
        get() = interpreter != null && queryEmojis.isNotEmpty() &&
            queryOutputIndex >= 0 && tokenToId.isNotEmpty()

    @Synchronized
    override fun suggestEmojis(query: String, limit: Int): List<String> {
        val itp = interpreter ?: return emptyList()
        if (query.isBlank() || limit <= 0 || !isAvailable) return emptyList()

        return try {
            val inputTensor = itp.getInputTensor(0)
            if (inputTensor.dataType() != DataType.INT32) return emptyList()

                val words = query.lowercase()
                    .replace(Regex("[^a-z0-9']+"), " ")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                if (words.isEmpty()) return emptyList()

            val seqLen = inputTensor.shape().let { if (it.size >= 2) it[it.size - 1] else 10 }
            val ids = IntArray(seqLen)
            var fill = 0
            ids[fill++] = bosId
            for (w in words) {
                if (fill >= seqLen) break
                ids[fill++] = tokenToId[w] ?: tokenToId[w.trimEnd('\'')] ?: unkId
            }
            // ids[fill..] stay 0 (= </S> doubles as the right-pad id this model was trained with)

            val outputs = Array(itp.outputTensorCount) { idx ->
                val shape = itp.getOutputTensor(idx).shape()
                Array(1) { FloatArray(shape.lastOrNull() ?: 1) }
            }
            val outputMap = HashMap<Int, Any>()
            for (idx in outputs.indices) outputMap[idx] = outputs[idx]
            itp.runForMultipleInputsOutputs(arrayOf(Array(1) { ids.copyOf() }), outputMap)

            val scores = outputs.getOrNull(queryOutputIndex) ?: return emptyList()
            val flat = scores[0]
            val wanted = queryEmojis.keys.maxOrNull() ?: return emptyList()

            val heap = java.util.PriorityQueue<Pair<Float, Int>>(limit + 1) { a, b ->
                a.first.compareTo(b.first)
            }
            for (qi in flat.indices) {
                if (qi > wanted || qi > flat.size) break
                if (!queryEmojis.containsKey(qi)) continue
                val s = flat[qi]
                if (!s.isFinite()) continue
                if (heap.size < limit * 3) {
                    heap.offer(s to qi)
                } else if (s > (heap.peek()?.first ?: Float.NEGATIVE_INFINITY)) {
                    heap.poll()
                    heap.offer(s to qi)
                }
            }

            val out = LinkedHashSet<String>()
            for ((_, qi) in heap.sortedByDescending { it.first }) {
                for (e in queryEmojis[qi].orEmpty()) {
                    out.add(e)
                    if (out.size >= limit) break
                }
                if (out.size >= limit) break
            }
            out.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
    }

    private fun findOutput(name: String): Int {
        val itp = interpreter ?: return -1
        for (i in 0 until itp.outputTensorCount) {
            if (itp.getOutputTensor(i).name() == name) return i
        }
        return -1
    }
}
