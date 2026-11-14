package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter

/**
 * Encodes a word-index into a 96-dim context embedding via TFLite.
 * Post-fix: model loaded via [AssetMmap] (memory-mapped).
 */
class TfliteContextEncoder(
    context: Context,
    modelPath: String = "ranking/context_encoder.tflite"
) : ContextEncoder {

    private val interpreter: Interpreter =
        TfliteModelLoader.load(context, modelPath, numThreads = 2)

    init {
        // Contract check: the encoder must emit a 96-float embedding. The bundled model emits
        // a scalar, so constructing it fails here and the caller falls back to "no encoder"
        // instead of throwing (and swallowing) on every encode().
        val outCount = interpreter.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
        if (outCount != 96) {
            interpreter.close()
            throw IllegalStateException("Context encoder output has $outCount values, expected 96")
        }
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
