package com.noircipher.shade.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.AssetMmap
import java.nio.IntBuffer

class OnnxNwpPredictor(
    context: Context,
    private val vocabulary: NeuralVocabulary
) : NeuralPredictor {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = run {
        val candidates = listOf(
            "languages/en/model_nwp_en.ort",
            "languages/en/model_nwp_en.onnx"
        )
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
        } ?: throw (lastError ?: IllegalStateException("No NWP model asset found"))

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ORT_ENABLE_ALL)
            setIntraOpNumThreads(2)
        }
        environment.createSession(modelBytes, opts)
    }

    private val inputName: String = session.inputNames.firstOrNull()
        ?: throw IllegalStateException("NWP model has no inputs")

    @Synchronized
    override fun predictLogits(contextIds: IntArray): FloatArray {
        val lastMeaningful = contextIds.lastOrNull {
            it != vocabulary.bosId && it != vocabulary.eosId
        } ?: vocabulary.unkId

        val buffer = IntBuffer.allocate(1)
        buffer.put(lastMeaningful)
        buffer.rewind()

        val input = OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1)
        )

        val result = session.run(mapOf(inputName to input))
        try {
            val output = result.get(0) as? OnnxTensor
                ?: throw IllegalStateException("NWP model returned no tensor output")
            val floatBuffer = output.floatBuffer
            val length = minOf(floatBuffer.remaining(), vocabulary.size)
            return FloatArray(length) { floatBuffer.get() }
        } finally {
            result.close()
            input.close()
        }
    }

    @Synchronized
    override fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w("OnnxNwpPredictor", "session.close() failed", e)
        }
    }
}
