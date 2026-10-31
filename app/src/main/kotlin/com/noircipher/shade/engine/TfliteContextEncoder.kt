package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter

/**
 * Encodes a word-index into a 96-dim context embedding via TFLite.
 * Post-fix: model loaded via [AssetMmap] (memory-mapped).
 */
class TfliteContextEncoder(
    context: Context,
    modelPath: String = "ranking/context_encoder_hi_xa.tflite"
) : ContextEncoder {

    private val interpreter: Interpreter = run {
        val modelBuffer = AssetMmap.load(context, modelPath)
        Interpreter(
            modelBuffer,
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
