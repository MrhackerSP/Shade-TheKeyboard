package com.noircipher.shade.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Neural next-word prediction (#25) over the bundled RNN LM
 * (`predictor/next_word.tflite` + `next_word_vocab.tsv`, a 30,007-word
 * English vocabulary with `</S>`/`<S>`/`<UNK>` as ordinary inline ids).
 *
 * The graph is one LSTM step: a word id in, a log-probability distribution
 * over the next word out. The LSTM state tensors are variable, so a suggestion
 * resets them and replays the last few context words, then reads the
 * distribution after the final word — i.e. P(next word | w1..wn).
 *
 * On the JVM (unit tests) the TFLite native library is unavailable; the class
 * then reports unavailable and returns no suggestions instead of crashing.
 */
class TfliteNextWordScorer(
    context: Context,
    modelPath: String = "predictor/next_word.tflite",
    vocabPath: String = "predictor/next_word_vocab.tsv"
) {

    /** Vocabulary: index = model id. */
    private val tokens: List<String> = AssetTsv.use(
        context, vocabPath, TAG, emptyList(),
        failureMessage = "next-word vocab failed to load; feature disabled"
    ) { lines ->
        val list = ArrayList<String>(32_000)
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val token = parts.getOrNull(1) ?: continue
            while (list.size <= id) list.add("")
            if (list[id].isEmpty()) list[id] = token
        }
        list
    }

    private val tokenToId: Map<String, Int> = HashMap<String, Int>(tokens.size * 2).apply {
        tokens.forEachIndexed { id, token -> if (token.isNotEmpty()) putIfAbsent(token, id) }
    }

    private val interpreter: Interpreter? = try {
        val itp = TfliteModelLoader.load(context, modelPath, numThreads = 2)
        val input = itp.getInputTensor(0)
        val output = itp.getOutputTensor(0)
        // Contract check: single INT32 word id in, one distribution over the
        // whole vocabulary out. Otherwise stay disabled (visible, not silent).
        val ok = input.dataType() == DataType.INT32 && input.numDimensions() == 1 &&
            output.shape().lastOrNull() == tokens.size
        if (ok) {
            itp
        } else {
            Log.w(TAG, "next-word model contract mismatch (expected vocab ${tokens.size}); feature disabled")
            itp.close()
            null
        }
        // Throwable, not Exception: a failed native-library load (UnsatisfiedLinkError) is
        // an Error and must disable the feature, not kill the IME process.
    } catch (t: Throwable) {
        null
    }

    /** Reused output buffer: one distribution over the vocabulary. */
    private val logits: Array<FloatArray> = Array(1) { FloatArray(tokens.size) }
    private val inputIds = IntArray(1)

    val isAvailable: Boolean
        get() = interpreter != null

    /**
     * Next-word suggestions for the given context (best first). Words outside
     * the LM vocabulary are skipped; an all-OOV context yields no suggestion.
     */
    @Synchronized
    fun suggest(contextWords: List<String>, limit: Int): List<String> {
        val itp = interpreter ?: return emptyList()
        if (limit <= 0) return emptyList()
        val ctx = contextWords.asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() && tokenToId.containsKey(it) }
            .toList()
            .takeLast(MAX_CONTEXT)
        if (ctx.isEmpty()) return emptyList()

        return try {
            // Stateful LM: zero the LSTM state, replay the context words, read
            // the next-word distribution after the final one.
            itp.resetVariableTensors()
            var dist: FloatArray? = null
            for (w in ctx) {
                inputIds[0] = tokenToId[w] ?: continue
                itp.run(inputIds, logits)
                dist = logits[0]
            }
            val scores = dist ?: return emptyList()
            // IntArray has no mapNotNull in the Kotlin stdlib; hop through
            // Iterable<Int> first (behavior identical, zero extra allocation beyond the iterator).
            topK(scores, MAX_RESULTS)
                .asIterable()
                .mapNotNull { id ->
                    val t = tokens.getOrNull(id) ?: return@mapNotNull null
                    if (isSuggestable(t)) t else null
                }
                .distinct()
                .take(limit)
        } catch (t: Throwable) {
            Log.w(TAG, "next-word prediction failed", t)
            emptyList()
        }
    }

    fun close() {
        try { interpreter?.close() } catch (t: Throwable) { Log.w(TAG, "Failed to close next-word interpreter", t) }
    }

    /** Word-shaped lowercase tokens only; keep "a"/"i", drop specials and punctuation. */
    private fun isSuggestable(token: String): Boolean {
        if (token.isEmpty() || token[0] == '<') return false
        if (token.length < 2 && token != "a" && token != "i") return false
        if (!token[0].isLetter()) return false
        return token.all { it.isLetterOrDigit() || it == '\'' || it == '-' }
    }

    private fun normalize(word: String): String =
        word.trim().lowercase().trimEnd('.', ',', '!', '?', ';', ':')

    /** Partial top-k over the log-probability array (k is tiny vs 30k entries). */
    private fun topK(a: FloatArray, k: Int): IntArray {
        val best = IntArray(k) { -1 }
        val vals = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in a.indices) {
            val v = a[i]
            if (!v.isFinite()) continue
            var j = k - 1
            if (v <= vals[j]) continue
            while (j > 0 && vals[j - 1] < v) {
                vals[j] = vals[j - 1]
                best[j] = best[j - 1]
                j--
            }
            vals[j] = v
            best[j] = i
        }
        return best.filter { it >= 0 }.toIntArray()
    }

    companion object {
        private const val TAG = "TfliteNextWordScorer"

        /** Context words replayed into the LM (older words have little effect). */
        private const val MAX_CONTEXT = 3

        /** Candidates pulled from the distribution before filtering. */
        private const val MAX_RESULTS = 16
    }
}
