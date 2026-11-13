package com.noircipher.shade.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device neural spell checker over the Gboard encoder/decoder package
 * (`assets/spell_checker/{encoder_0,decoder_init_0,decoder_step_0}.tflite` +
 * `wpm.vocab`).
 *
 * Model contract (verified by decoding against the models, see the wiring
 * report):
 *  - The encoder consumes WordPiece ids of the word **in reverse piece order**
 *    followed by `</s>` (the models were trained with reversed sources), plus
 *    float paddings (0 = real position, 1 = pad). Output: source encodings
 *    FLOAT32 `[1,T,384]`.
 *  - `decoder_init` takes `num_hyps`, the source paddings and the encodings,
 *    and emits the attention-projected source vectors (`source_vecs`, fed to
 *    decoder step input 1) and the initial attention context (decoder step
 *    input 8). Input 2 (`source_contexts`) is not consumed by the graph — any
 *    `[1,T,384]` tensor works; we feed the encodings.
 *  - `decoder_step` is one autoregressive LSTM+attention step: inputs 4..7 are
 *    the two LSTM layers' memory/cell states (order `m0, c1, m1, c0` by tensor
 *    name), fed back unchanged from outputs 2..5; input 8 is the attention
 *    context fed back from output 6. Output 1 holds log-probabilities over the
 *    16,000-piece vocabulary.
 *
 * Inference runs at a fixed T (`MAX_T`) with tail padding instead of resizing
 * per word: a one-time `resizeInput` + `allocateTensors` keeps interpreters
 * reusable across words (repeated resizes were observed to corrupt state in
 * the PoC). Beam search (default width 3) is budget-guarded so a slow device
 * still gets the greedy result.
 */
class NeuralSpellChecker(
    context: Context,
    private val beamWidth: Int = 3,
    private val budgetMs: Long = 150L
) {

    private val pieces: List<String>
    private val pieceToId: HashMap<String, Int>
    private val maxPieceLen: Int
    private val unkId: Int
    private val sosId: Int
    private val eosId: Int

    private val encoder: Interpreter?
    private val decoderInit: Interpreter?
    private val decoderStep: Interpreter?

    // Fixed-shape input arrays (reused across invocations; single-threaded use).
    private val srcIds = Array(1) { IntArray(MAX_T) }
    private val srcPads = Array(1) { FloatArray(MAX_T) }
    private val encodings = Array(1) { Array(MAX_T) { FloatArray(D) } }
    private val numHyps = Array(1) { IntArray(1) }
    private val vecs = Array(1) { Array(MAX_T) { FloatArray(D) } }
    private val stepIds = Array(1) { IntArray(1) }
    private val logits = FloatArray(VOCAB)
    private val stIn = Array(4) { Array(1) { FloatArray(D) } }
    private val stOut = Array(4) { FloatArray(D) }
    private val ctx = Array(1) { FloatArray(D) }
    private val ctxOut = Array(1) { FloatArray(D) }

    private val loaded = AtomicBoolean(false)

    init {
        val vocab = try {
            loadVocab(context)
        } catch (t: Throwable) {
            Log.w(TAG, "wpm.vocab failed to load; feature disabled", t)
            emptyList<String>() to HashMap<String, Int>()
        }
        pieces = vocab.first
        pieceToId = vocab.second
        maxPieceLen = pieces.maxOfOrNull { it.length } ?: 1
        unkId = pieceToId["<unk>"] ?: 0
        sosId = pieceToId["<s>"] ?: 1
        eosId = pieceToId["</s>"] ?: 2

        var enc: Interpreter? = null
        var init: Interpreter? = null
        var step: Interpreter? = null
        try {
            enc = Interpreter(AssetMmap.load(context, ENCODER_PATH), opts())
            init = Interpreter(AssetMmap.load(context, DECODER_INIT_PATH), opts())
            step = Interpreter(AssetMmap.load(context, DECODER_STEP_PATH), opts())
            // One-time shape fix + allocation; no per-word resizes afterwards.
            enc.resizeInput(0, intArrayOf(1, MAX_T))
            enc.resizeInput(1, intArrayOf(1, MAX_T))
            enc.allocateTensors()
            init.resizeInput(1, intArrayOf(1, MAX_T))
            init.resizeInput(2, intArrayOf(1, MAX_T, D))
            init.allocateTensors()
            step.resizeInput(1, intArrayOf(1, MAX_T, D))
            step.resizeInput(2, intArrayOf(1, MAX_T, D))
            step.resizeInput(3, intArrayOf(1, MAX_T))
            step.allocateTensors()
            loaded.set(true)
            // Throwable, not Exception: a failed native-library load (UnsatisfiedLinkError) is
            // an Error and would otherwise escape this guard and kill the whole IME process.
        } catch (t: Throwable) {
            Log.w(TAG, "spell checker models failed to load; feature disabled", t)
            try { enc?.close() } catch (_: Throwable) {}
            try { init?.close() } catch (_: Throwable) {}
            try { step?.close() } catch (_: Throwable) {}
            enc = null
            init = null
            step = null
        }
        encoder = enc
        decoderInit = init
        decoderStep = step
    }

    val isAvailable: Boolean
        get() = loaded.get() && pieces.isNotEmpty() &&
            encoder != null && decoderInit != null && decoderStep != null

    /** Page the models in and verify the pipeline once, off the hot path. */
    fun warmUp() {
        if (isAvailable) runCatching { correct("hello", limit = 1, beamWidthOverride = 1) }
    }

    /**
     * Ranked corrections for a misspelled word (best first). The typed word
     * itself is never returned, so an empty result means "nothing better
     * than what the user typed". Case of [word] is mirrored onto results.
     */
    @Synchronized
    fun correct(word: String, limit: Int = 3, beamWidthOverride: Int = beamWidth): List<String> {
        if (!isAvailable || word.length < 2 || word.length > MAX_WORD_CHARS) return emptyList()
        return try {
            val deadline = System.nanoTime() + budgetMs * 1_000_000
            val raw = decode(word, beamWidthOverride, deadline)
            val typed = word.lowercase()
            raw.asSequence()
                .filter { it.isNotBlank() && it.lowercase() != typed }
                .distinctBy { it.lowercase() }
                .take(limit)
                .map { matchCase(word, it) }
                .toList()
        } catch (t: Throwable) {
            Log.w(TAG, "neural correction failed for '$word'", t)
            emptyList()
        }
    }

    fun close() {
        try { encoder?.close() } catch (_: Throwable) {}
        try { decoderInit?.close() } catch (_: Throwable) {}
        try { decoderStep?.close() } catch (_: Throwable) {}
        loaded.set(false)
    }

    // ------------------------------------------------------------------ decode

    private class Hyp(
        val score: Float,
        val ids: IntArray,
        val states: Array<FloatArray>,
        val ctx: FloatArray
    )

    private fun decode(word: String, beamWidth: Int, deadlineNs: Long): List<String> {
        val enc = encoder ?: return emptyList()
        val init = decoderInit ?: return emptyList()
        val step = decoderStep ?: return emptyList()

        val seg = segment(word).map { pieceToId[it] ?: unkId }
        if (seg.size + 1 > MAX_T) return emptyList()
        java.util.Arrays.fill(srcIds[0], 0)
        java.util.Arrays.fill(srcPads[0], 1f)
        var j = 0
        for (i in seg.indices.reversed()) {
            srcIds[0][j] = seg[i]
            j++
        }
        srcIds[0][j] = eosId                       // reversed pieces + trailing </s>
        val used = seg.size + 1
        for (i in 0 until used) srcPads[0][i] = 0f

        // Encoder: source encodings.
        enc.runForMultipleInputsOutputs(arrayOf<Any>(srcIds, srcPads), mapOf(0 to encodings))

        // Decoder init: source vectors + initial attention context.
        numHyps[0][0] = 1
        init.runForMultipleInputsOutputs(
            arrayOf<Any>(numHyps, srcPads, encodings),
            mapOf(1 to vecs, 2 to ctx)
        )
        val initCtx = FloatArray(D)
        System.arraycopy(ctx[0], 0, initCtx, 0, D)

        fun zeroStates() = Array(4) { FloatArray(D) }

        // Greedy pass first: cheap and always available within any budget.
        val states = zeroStates()
        var curCtx = initCtx.copyOf()
        val greedyIds = ArrayList<Int>()
        var ended = false
        for (t in 0 until MAX_STEPS) {
            invokeStep(step, if (greedyIds.isEmpty()) sosId else greedyIds.last(),
                states, curCtx)
            val am = argmax(logits)
            if (am == eosId) {
                ended = true
                break
            }
            greedyIds.add(am)
            for (k in 0..3) System.arraycopy(stOut[k], 0, states[k], 0, D)
            System.arraycopy(ctxOut[0], 0, curCtx, 0, D)
        }
        val results = ArrayList<Pair<Float, String>>()
        if (ended && greedyIds.isNotEmpty()) {
            results.add(0f to detokenize(greedyIds.toIntArray()))
        }

        // Beam pass (budget-guarded): ranked alternatives.
        if (beamWidth > 1) {
            var hyps = listOf(Hyp(0f, intArrayOf(), zeroStates(), initCtx.copyOf()))
            val done = ArrayList<Pair<Float, IntArray>>()
            for (t in 0 until MAX_STEPS) {
                if (System.nanoTime() > deadlineNs) break
                val cands = ArrayList<Triple<Float, Int, Hyp>>()
                for (h in hyps) {
                    val lastId = if (h.ids.isEmpty()) sosId else h.ids.last()
                    invokeStep(step, lastId, h.states, h.ctx)
                    val ctxNext = ctxOut[0].copyOf()
                    val stNext = Array(4) { i -> stOut[i].copyOf() }
                    for (k in topK(beamWidth + 1)) {
                        cands.add(Triple(h.score + logits[k], k,
                            Hyp(h.score + logits[k], h.ids + k, stNext, ctxNext)))
                    }
                }
                cands.sortByDescending { it.first }
                val next = ArrayList<Hyp>()
                for ((score, k, h) in cands) {
                    if (k == eosId) done.add(score to h.ids)
                    else {
                        next.add(h)
                        if (next.size >= beamWidth) break
                    }
                }
                hyps = next
                if (hyps.isEmpty()) break
            }
            done.sortByDescending { it.first }
            for ((score, seq) in done) {
                if (seq.isNotEmpty()) results.add(score to detokenize(seq))
            }
        }
        return results.asSequence()
            .map { it.second }
            .distinctBy { it.lowercase() }
            .toList()
    }

    /** One decoder step: fill inputs from (lastId, states, curCtx), invoke, read outputs. */
    private fun invokeStep(step: Interpreter, lastId: Int, states: Array<FloatArray>,
                           curCtx: FloatArray) {
        stepIds[0][0] = lastId
        for (k in 0..3) System.arraycopy(states[k], 0, stIn[k][0], 0, D)
        System.arraycopy(curCtx, 0, ctx[0], 0, D)
        step.runForMultipleInputsOutputs(
            arrayOf<Any>(stepIds, vecs, encodings, srcPads, stIn[0], stIn[1], stIn[2], stIn[3], ctx),
            mapOf(1 to logits, 2 to stOut[0], 3 to stOut[1], 4 to stOut[2], 5 to stOut[3],
                6 to ctxOut)
        )
    }

    private fun argmax(a: FloatArray): Int {
        var best = 0
        for (i in 1 until a.size) if (a[i] > a[best]) best = i
        return best
    }

    private fun topK(k: Int): IntArray {
        // Partial selection over logits: k is tiny (beam+1), VOCAB is 16k.
        val best = IntArray(k) { -1 }
        val vals = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in logits.indices) {
            val v = logits[i]
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
        return best
    }

    private fun detokenize(ids: IntArray): String =
        ids.joinToString("") { pieces.getOrElse(it) { "" } }.replace("##", "")

    // ------------------------------------------------------------------ text

    /** Greedy longest-match WordPiece segmentation (lowercase, max [MAX_PIECES]). */
    internal fun segment(word: String): List<String> {
        val w = word.lowercase()
        val out = ArrayList<String>(MAX_PIECES)
        var i = 0
        while (i < w.length && out.size < MAX_PIECES) {
            var best: String? = null
            var bestEnd = i
            val stop = minOf(w.length, i + maxPieceLen)
            for (j in stop downTo i + 1) {
                val cand = (if (i == 0) w.substring(i, j) else "##" + w.substring(i, j))
                if (pieceToId.containsKey(cand)) {
                    best = cand
                    bestEnd = j
                    break
                }
            }
            if (best == null) {
                out.add("<unk>")
                i += 1
            } else {
                out.add(best)
                i = bestEnd
            }
        }
        return out
    }

    /** Mirror the typed word's case pattern onto a correction. */
    internal fun matchCase(typed: String, correction: String): String = when {
        typed.all { !it.isLetter() || it.isLowerCase() } -> correction.lowercase()
        typed.firstOrNull()?.isUpperCase() == true &&
            typed.drop(1).all { !it.isLetter() || it.isLowerCase() } ->
            correction.lowercase().replaceFirstChar { it.uppercaseChar() }
        else -> correction
    }

    private fun loadVocab(context: Context): Pair<List<String>, HashMap<String, Int>> {
        val list = ArrayList<String>(VOCAB)
        context.assets.open(VOCAB_PATH).bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.trimEnd('\n', '\r')
                if (p.isNotEmpty()) list.add(p)
            }
        }
        val arr = list.toTypedArray()
        val map = HashMap<String, Int>(arr.size * 2)
        arr.forEachIndexed { i, p -> map.putIfAbsent(p, i) }
        return arr.toList() to map
    }

    companion object {
        private const val TAG = "NeuralSpellChecker"
        private const val ENCODER_PATH = "spell_checker/encoder_0.tflite"
        private const val DECODER_INIT_PATH = "spell_checker/decoder_init_0.tflite"
        private const val DECODER_STEP_PATH = "spell_checker/decoder_step_0.tflite"
        private const val VOCAB_PATH = "spell_checker/wpm.vocab"
        private const val VOCAB = 16_000
        private const val D = 384

        /** Fixed source length: [MAX_PIECES] reversed pieces + `</s>`, plus headroom. */
        private const val MAX_T = 24
        internal const val MAX_PIECES = 16
        private const val MAX_STEPS = 12
        private const val MAX_WORD_CHARS = 24

        private fun opts(): Interpreter.Options = Interpreter.Options().setNumThreads(2)
    }
}
