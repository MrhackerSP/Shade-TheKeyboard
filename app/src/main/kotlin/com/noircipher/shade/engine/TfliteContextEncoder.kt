package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter

class TfliteContextEncoder(
    context: Context,
    modelPath: String = "ranking/context_encoder_hi_xa.tflite"
) : ContextEncoder {

    private val interpreter: Interpreter = Interpreter(
        loadModelBytes(context, modelPath),
        Interpreter.Options().apply {
            numThreads = 2
        }
    )

    private val input1d = IntArray(1)
    private val input2d: Array<IntArray> = Array(1) { IntArray(1) }
    private val output: Array<FloatArray> = Array(1) { FloatArray(96) }

    @Synchronized
    override fun encode(wordIndex: Int): FloatArray {
        output[0].fill(0f)
        val inputRank = interpreter.getInputTensor(0).numDimensions()
        if (inputRank == 1) {
            input1d[0] = wordIndex
            interpreter.run(input1d, output)
        } else {
            input2d[0][0] = wordIndex
            interpreter.run(input2d, output)
        }
        return output[0].copyOf()
    }

    @Synchronized
    override fun close() {
        interpreter.close()
    }

    private fun loadModelBytes(context: Context, modelPath: String): ByteArray {
        val bytes = context.assets.open(modelPath).readBytes()
        if (bytes.size > 32 && bytes.copyOfRange(0, 4).contentEquals("TFL3".toByteArray())) {
            val fixed = bytes.copyOf()
            val rootOffset = 32
            fixed[0] = (rootOffset and 0xff).toByte()
            fixed[1] = ((rootOffset shr 8) and 0xff).toByte()
            fixed[2] = ((rootOffset shr 16) and 0xff).toByte()
            fixed[3] = ((rootOffset shr 24) and 0xff).toByte()
            fixed[4] = 'T'.code.toByte()
            fixed[5] = 'F'.code.toByte()
            fixed[6] = 'L'.code.toByte()
            fixed[7] = '3'.code.toByte()
            return fixed
        }
        return bytes
    }
}
