package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteContextEncoder(
    context: Context,
    modelPath: String = "ranking/context_encoder_hi_xa.tflite"
) : ContextEncoder {

    private val interpreter: Interpreter = run {
        val bytes = context.assets.open(modelPath).readBytes()
        Interpreter(
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes),
            Interpreter.Options().apply {
                numThreads = 2
            }
        )
    }

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

}
