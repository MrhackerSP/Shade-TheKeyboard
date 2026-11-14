package com.noircipher.shade.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter

/**
 * Concept-triggered emoji suggestions (#26).
 *
 * Two layers, merged in priority order:
 *  1. Curated trigger rules (`concept_rules.tsv`: word/phrase -> concept names,
 *     and `concept_emoji_rules.tsv`: word/phrase -> direct emojis). Pure data,
 *     zero cost, highest precision — the longest matching trigger wins.
 *  2. The bundled concept model (`concept_model.tflite`): tokenizes the query,
 *     scores it against the 106 expression concepts and maps the top ones to
 *     their weight-ordered emojis (`concepts.tsv` -> `concept_emojis.tsv`).
 *
 * The model path is validated at load time with probe phrases ("good luck"
 * must rank the good_luck concept, ...). If the probes don't pass — e.g. the
 * TFLite native library is missing on the JVM, the tokenizer id convention
 * doesn't hold, or (as verified offline with the real interpreter) the
 * bundled graph's 106 output rows don't follow the concepts.tsv trie order —
 * the model path disables itself and only the rules stay active. Visible,
 * not silent.
 *
 * Tokenizer note: unlike the expression model, this graph's token.csym keeps
 * `<S>`/`</S>`/`<UNK>` inline at their trie ids (421-423), not at 0/1/2 — the
 * regenerated `concept_vocab.tsv` carries the ids this class looks up, so no
 * remap constant is needed here; probes decide at load time whether the whole
 * id convention actually holds for this graph.
 */
class TfliteConceptScorer(
    context: Context,
    modelPath: String = "emoji_expression/concept_model.tflite",
    vocabPath: String = "emoji_expression/concept_vocab.tsv",
    conceptsPath: String = "emoji_expression/concepts.tsv",
    emojiMapPath: String = "emoji_expression/concept_emojis.tsv",
    rulesPath: String = "emoji_expression/concept_rules.tsv",
    directRulesPath: String = "emoji_expression/concept_emoji_rules.tsv"
) : ExpressionScorer {

    /** Interpreter + index of the concept-probability output, or null if the model path is disabled. */
    private class ModelState(val interpreter: Interpreter, val probabilitiesIndex: Int)

    /**
     * Non-null only while the model path is live. Cleared (and the native
     * interpreter freed) when the load-time probes fail — the rules layer
     * never needs the graph, so there is no reason to keep it resident.
     */
    @Volatile
    private var modelState: ModelState? = null

    /** Tokenizer vocab: word -> model id (direct token.csym trie ids; `<S>`=422, `</S>`=421, `<UNK>`=423). */
    private val tokenToId: Map<String, Int> = AssetTsv.use(
        context, vocabPath, TAG, emptyMap(),
        failureMessage = "concept vocab failed to load; model path disabled"
    ) { lines ->
        val map = HashMap<String, Int>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val token = parts.getOrNull(1) ?: continue
            if (token.isNotEmpty()) map.putIfAbsent(token, id)
        }
        map
    }

    /** Model concept id -> concept name. */
    private val conceptNames: List<String> = AssetTsv.use(
        context, conceptsPath, TAG, emptyList(),
        failureMessage = "concept list failed to load; model path disabled"
    ) { lines ->
        lines.filter { it.isNotEmpty() && it[0] != '#' }
            .mapNotNull { line ->
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                id to name
            }
            .toMutableList()
            .apply { sortBy { it.first } }
            .map { it.second }
    }

    /** Concept name -> weight-ordered emoji candidates. */
    private val conceptEmojis: Map<String, List<String>> = AssetTsv.use(
        context, emojiMapPath, TAG, emptyMap(),
        failureMessage = "concept emoji map failed to load; rules stay active"
    ) { lines ->
        val map = HashMap<String, List<String>>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split('\t')
            val name = parts.getOrNull(0)?.trim() ?: continue
            val emojis = parts.getOrNull(1)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
            if (name.isNotEmpty() && emojis.isNotEmpty()) map[name] = emojis
        }
        map
    }

    /** Trigger word/phrase -> concept names (priority order). */
    private val conceptRules: Map<String, List<String>> = loadRules(context, rulesPath)

    /** Trigger word/phrase -> direct emoji candidates (priority order). */
    private val directRules: Map<String, List<String>> = loadRules(context, directRulesPath)

    /** Longest trigger (in words) across both rule tables, for suffix matching. */
    private val maxTriggerWords: Int = (conceptRules.keys + directRules.keys)
        .maxOfOrNull { it.split(' ').size } ?: 0

    init {
        modelState = try {
            val itp = TfliteModelLoader.load(context, modelPath, numThreads = 2)
            val idx = findProbabilitiesOutput(itp)
            val contractOk = idx >= 0 && itp.getOutputTensor(idx).shape().lastOrNull() == conceptNames.size
            if (contractOk) {
                ModelState(itp, idx)
            } else {
                Log.w(TAG, "concept model contract mismatch (probabilities output vs ${conceptNames.size} concepts); model path disabled")
                itp.close()
                null
            }
            // Throwable, not Exception: a failed native-library load (UnsatisfiedLinkError) is
            // an Error and must disable the model path, not kill the IME process.
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Load-time self-check: the probe phrases must rank their expected concepts.
     * Guards against a wrong tokenizer id convention producing plausible-looking
     * garbage; the rules layer keeps working either way.
     */
    private val probesPassed: Boolean = run {
        val ms = modelState ?: return@run false
        var passed = 0
        for ((phrase, expected) in PROBES) {
            if (expected in modelConcepts(phrase, 5)) passed++
        }
        if (passed < MIN_PROBES_PASS) {
            Log.w(TAG, "concept model probes passed $passed/${PROBES.size}; model path disabled, rules stay active")
            // Free the graph — the rules layer is pure data and never touches it.
            try {
                ms.interpreter.close()
            } catch (t: Throwable) {
                Log.w(TAG, "concept interpreter close failed", t)
            }
            modelState = null
        }
        passed >= MIN_PROBES_PASS
    }

    override val isAvailable: Boolean
        get() = conceptRules.isNotEmpty() || directRules.isNotEmpty() ||
            (modelState != null && probesPassed)

    @Synchronized
    override fun suggestEmojis(query: String, limit: Int): List<String> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val norm = normalize(query)
        if (norm.isEmpty()) return emptyList()
        val words = norm.split(' ')

        val out = LinkedHashSet<String>()

        // Layer 1a — direct emoji triggers, longest matching trigger wins.
        matchTrigger(words, directRules)?.let { out.addAll(it) }

        // Layer 1b — concept triggers mapped through the emoji table.
        matchTrigger(words, conceptRules)?.forEach { concept ->
            out.addAll(conceptEmojis[concept].orEmpty())
        }

        // Layer 2 — the concept model, if its probes passed at load time.
        if (out.size < limit && modelState != null && probesPassed) {
            for (concept in modelConcepts(norm, 2)) {
                out.addAll(conceptEmojis[concept].orEmpty())
                if (out.size >= limit) break
            }
        }
        return out.toList().take(limit)
    }

    @Synchronized
    override fun close() {
        modelState?.interpreter?.close()
        modelState = null
    }

    // ------------------------------------------------------------------ rules

    /** Longest-suffix trigger match: checks the whole phrase first, then progressively shorter tails. */
    private fun matchTrigger(words: List<String>, rules: Map<String, List<String>>): List<String>? {
        val max = minOf(maxTriggerWords, words.size)
        for (n in max downTo 1) {
            rules[words.takeLast(n).joinToString(" ")]?.let { return it }
        }
        return null
    }

    private fun loadRules(context: Context, path: String): Map<String, List<String>> =
        AssetTsv.use(context, path, TAG, emptyMap(), failureMessage = "$path failed to load") { lines ->
            val map = HashMap<String, List<String>>()
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val parts = line.split('\t')
                val trigger = parts.getOrNull(0)?.trim() ?: continue
                val targets = parts.getOrNull(1)?.split('|')?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }.orEmpty()
                if (trigger.isNotEmpty() && targets.isNotEmpty()) map[trigger] = targets
            }
            map
        }

    // ------------------------------------------------------------------ model

    private fun findProbabilitiesOutput(itp: Interpreter): Int {
        for (i in 0 until itp.outputTensorCount) {
            if (itp.getOutputTensor(i).name() == PROBABILITIES_NAME) return i
        }
        // Fall back to the first output sized like the concept list.
        for (i in 0 until itp.outputTensorCount) {
            val last = itp.getOutputTensor(i).shape().lastOrNull() ?: 0
            if (conceptNames.isNotEmpty() && last == conceptNames.size) return i
        }
        return -1
    }

    /** Ids resolved from the vocab (fall back to the trie constants this graph shipped with). */
    private val bosId: Int get() = tokenToId["<S>"] ?: 422
    private val eosId: Int get() = tokenToId["</S>"] ?: 421
    private val unkId: Int get() = tokenToId["<UNK>"] ?: 423

    /** Top concept names for a normalized query, best first. Empty when the model path is off. */
    private fun modelConcepts(normQuery: String, k: Int): List<String> {
        val ms = modelState ?: return emptyList()
        if (tokenToId.isEmpty() || conceptNames.isEmpty()) return emptyList()
        return try {
            val ids = IntArray(MAX_TOKENS)
            var fill = 0
            ids[fill++] = bosId
            for (w in normQuery.split(' ')) {
                if (fill >= MAX_TOKENS) break
                ids[fill++] = tokenToId[w] ?: unkId
            }
            // Right-pad with </S>, matching the expression-model scheme this
            // pipeline ships (verified there: "0 = </S> doubles as the pad id").
            for (i in fill until MAX_TOKENS) ids[i] = eosId
            val probs = Array(1) { FloatArray(conceptNames.size) }
            ms.interpreter.runForMultipleInputsOutputs(arrayOf<Any>(ids), mapOf(ms.probabilitiesIndex to probs))

            probs[0]
                .withIndex()
                .filter { it.value >= MIN_CONCEPT_PROB && it.index < conceptNames.size }
                .sortedByDescending { it.value }
                .take(k)
                .map { conceptNames[it.index] }
                .filter { it != "neutral" && conceptEmojis.containsKey(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "concept model inference failed", t)
            emptyList()
        }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase()
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim()

    companion object {
        private const val TAG = "TfliteConceptScorer"

        /** Model input tensor shape [MAX_TOKENS] (INT32 token ids). */
        private const val MAX_TOKENS = 10

        /** Concept probabilities below this are treated as noise. */
        private const val MIN_CONCEPT_PROB = 0.10f

        /** The model path stays enabled only if at least this many probes pass. */
        private const val MIN_PROBES_PASS = 2

        private const val PROBABILITIES_NAME = "Inference/Probabilities"

        /** (query, expected concept) pairs that must rank in the top 5. */
        private val PROBES = listOf(
            "good luck" to "good_luck",
            "happy birthday" to "birthday",
            "heart" to "heart"
        )
    }
}
