package com.noircipher.shade.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.AssetMmap
import java.nio.LongBuffer

class OnnxNeuralPredictor(
    context: Context,
    private val vocabulary: NeuralVocabulary,
    private val contextSize: Int = 128,
    languageTag: String = "en"
) : NeuralPredictor {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = run {
        val candidates = buildList {
            add("languages/${languageTag}/model_${languageTag}.ort")
            add("languages/${languageTag}/model.ort")
            add("neural/model_${languageTag}.ort")
            add("neural/model.ort")
        }
        var lastError: Exception? = null
        val modelBytes: ByteArray? = candidates.firstNotNullOfOrNull { path ->
            try {
                AssetMmap.load(context, path).let { buf ->
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    arr
                }
            } catch (e: Exception) {
                lastError = e
                null
            }
        } ?: candidates.firstNotNullOfOrNull { path ->
            try {
                context.assets.open(path).use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        } ?: throw (lastError ?: IllegalStateException("No ONNX model asset found"))

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        environment.createSession(modelBytes, opts)
    }

    private val inputName: String = session.inputNames.firstOrNull()
        ?: throw IllegalStateException("ONNX model has no inputs")

    private val fixedInputLength: Int? = run {
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        val dim = shape?.getOrNull(1)
        if (dim != null && dim > 0) dim.toInt() else null
    }

    @Synchronized
    override fun predictLogits(contextIds: IntArray): FloatArray {
        // Vocabularies without [BOS] (hi/hg) would otherwise put -1 ids into the model — ONNX
        // Gather silently wraps negative indices to the LAST embedding row, corrupting context.
        // Pad with the unknown token (or 0) instead, and only prepend BOS when it exists.
        val padId = if (vocabulary.bosId >= 0) vocabulary.bosId
        else if (vocabulary.unkId >= 0) vocabulary.unkId else 0
        val meaningful = contextIds.filter {
            it >= 0 && it != vocabulary.bosId && it != vocabulary.eosId
        }
        val compact = (if (vocabulary.bosId >= 0) listOf(vocabulary.bosId) + meaningful else meaningful)
            .takeLast(contextSize + 1)
        val inputLength = fixedInputLength ?: compact.size.coerceAtLeast(1)

        val ids = IntArray(inputLength) { padId }
        val copyLength = minOf(compact.size, inputLength)
        val sourceStart = compact.size - copyLength
        for (i in 0 until copyLength) {
            ids[inputLength - copyLength + i] = compact[sourceStart + i]
        }

        val buffer = LongBuffer.allocate(inputLength)
        for (id in ids) buffer.put(id.toLong())
        buffer.rewind()

        val input = OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, inputLength.toLong())
        )

        val result = try {
            session.run(mapOf(inputName to input))
        } catch (t: Throwable) {
            try { input.close() } catch (_: Exception) {}
            throw t
        }
        try {
            val output = result.get(0) as? OnnxTensor
                ?: throw IllegalStateException("ONNX model returned no tensor output")
            val floatBuffer = output.floatBuffer
            val length = minOf(floatBuffer.remaining(), vocabulary.size)
            val logits = FloatArray(length) { floatBuffer.get() }

            // Penalize only the most recent repeated tokens; penalizing every token in
            // the whole context makes common function words too unlikely and erodes
            // suggestions in long conversations.
            val penalty = 0.8f
            val recent = meaningful.takeLast(8)
            val seen = HashSet<Int>(recent.size + 1)
            seen.add(padId)
            for (id in recent) seen.add(id)
            for (id in seen) {
                if (id in 0 until length) logits[id] -= penalty
            }
            return logits
        } finally {
            try { result.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w("OnnxNeuralPredictor", "session.close() failed", e)
        }
    }
}
