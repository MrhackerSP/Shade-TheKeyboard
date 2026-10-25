package com.noircipher.shade.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

class OnnxNeuralPredictor(
    context: Context,
    private val vocabulary: NeuralVocabulary,
    private val contextSize: Int = 16
) : NeuralPredictor {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = environment.createSession(
        context.assets.open("neural/model.ort").readBytes(),
        OrtSession.SessionOptions()
    )

    private val inputName: String = session.inputNames.first()

    override fun predictLogits(contextIds: IntArray): FloatArray {
        val ids = IntArray(contextSize)
        val copyLength = minOf(contextIds.size, contextSize)
        val sourceStart = contextIds.size - copyLength

        for (i in 0 until copyLength) {
            ids[contextSize - copyLength + i] = contextIds[sourceStart + i]
        }

        val buffer = LongBuffer.allocate(contextSize)
        for (id in ids) buffer.put(id.toLong())
        buffer.rewind()

        val input = OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, contextSize.toLong())
        )

        val result = session.run(mapOf(inputName to input))
        try {
            val output = result.get(0) as OnnxTensor
            val floatBuffer = output.floatBuffer
            val length = minOf(floatBuffer.remaining(), vocabulary.size)
            return FloatArray(length) { floatBuffer.get() }
        } finally {
            result.close()
            input.close()
        }
    }

    override fun close() {
        session.close()
    }
}
