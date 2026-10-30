package com.noircipher.shade.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.nio.LongBuffer

class OnnxNeuralPredictor(
    context: Context,
    private val vocabulary: NeuralVocabulary,
    private val contextSize: Int = 24,
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
        val modelBytes = candidates.firstNotNullOfOrNull { path ->
            try {
                context.assets.open(path).readBytes()
            } catch (e: Exception) {
                lastError = e
                null
            }
        } ?: throw (lastError ?: IllegalStateException("No ONNX model asset found"))
        environment.createSession(modelBytes, OrtSession.SessionOptions())
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
        val meaningful = contextIds.filter { it != vocabulary.bosId && it != vocabulary.eosId }
        val compact = (listOf(vocabulary.bosId) + meaningful).takeLast(contextSize + 1)
        val inputLength = fixedInputLength ?: compact.size

        val ids = IntArray(inputLength) { vocabulary.bosId }
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
            return FloatArray(length) { floatBuffer.get() }
        } finally {
            try { result.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}
