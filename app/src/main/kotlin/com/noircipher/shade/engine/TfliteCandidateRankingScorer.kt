package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteCandidateRankingScorer(
    context: Context,
    modelPath: String = "ranking/candidate_ranking_en.tflite"
) : CandidateRankingScorer {

    private val interpreter: Interpreter = Interpreter(
        ByteBuffer.allocateDirect(loadModelBytes(context, modelPath).size)
            .order(ByteOrder.nativeOrder())
            .put(loadModelBytes(context, modelPath)),
        Interpreter.Options().apply {
            numThreads = 2
        }
    )

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

    private fun loadModelBytes(context: Context, modelPath: String): ByteArray {
        val bytes = context.assets.open(modelPath).readBytes()
        // Some decoded assets carry the identifier at byte 0. Repair the
        // FlatBuffer header in memory so the verifier sees a valid root.
        if (bytes.size > 32 && bytes.copyOfRange(0, 4).contentEquals("TFL3".toByteArray())) {
            val fixed = bytes.copyOf()
            val rootOffset = if (modelPath.contains("context_encoder")) 32 else 28
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
