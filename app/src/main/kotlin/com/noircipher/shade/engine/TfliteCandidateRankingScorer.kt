package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteCandidateRankingScorer(
    context: Context,
    modelPath: String = "ranking/candidate_ranking_en.tflite"
) : CandidateRankingScorer {

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

    private val input: Array<FloatArray> = Array(1) { FloatArray(83) }
    private val output: Array<FloatArray> = Array(1) { FloatArray(1) }

    @Synchronized
    override fun score(features: FloatArray): Float {
        require(features.size == 83) { "Expected 83 ranking features, got ${features.size}" }
        input[0] = features.copyOf(83)
        output[0][0] = 0f
        interpreter.run(input, output)
        return output[0][0]
    }

    @Synchronized
    override fun close() {
        interpreter.close()
    }

}
