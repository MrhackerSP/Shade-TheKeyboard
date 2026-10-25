package com.noircipher.shade.ai

import java.io.Closeable

interface NeuralPredictor : Closeable {
    fun predictLogits(contextIds: IntArray): FloatArray
}
