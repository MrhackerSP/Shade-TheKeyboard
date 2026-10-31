package com.noircipher.shade.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Scores candidate feature vectors through the TFLite ranker model.
 *
 * Post-fix:
 *  - Reuses input/output buffers across calls (no per-call copyOf).
 *  - Caches the resized batch shape in [maxBatchSize] (resize happens at
 *    most once per batch size, not per call).
 *  - Falls back to per-item scoring transparently if the model rejects the
 *    resize.
 *  - Model loaded via [AssetMmap] (memory-mapped) instead of readBytes().
 */
class TfliteCandidateRankingScorer(
    context: Context,
    modelPath: String = "ranking/candidate_ranking_en.tflite"
) : CandidateRankingScorer {

    private val interpreter: Interpreter = run {
        val modelBuffer = AssetMmap.load(context, modelPath)
        Interpreter(
            modelBuffer,
            Interpreter.Options().apply {
                numThreads = 2
            }
        )
    }

    private val input: Array<FloatArray> = Array(1) { FloatArray(FEATURE_SIZE) }
    private val output: Array<FloatArray> = Array(1) { FloatArray(1) }

    @Volatile
    private var maxBatchSize: Int = 1
    private val batchLock = Any()

    @Synchronized
    override fun score(features: FloatArray): Float {
        require(features.size == FEATURE_SIZE) {
            "Expected $FEATURE_SIZE ranking features, got ${features.size}"
        }
        System.arraycopy(features, 0, input[0], 0, FEATURE_SIZE)
        output[0][0] = 0f
        interpreter.run(input, output)
        return output[0][0]
    }

    @Synchronized
    override fun scoreBatch(batch: List<FloatArray>): FloatArray {
        if (batch.isEmpty()) return FloatArray(0)
        batch.forEach { require(it.size == FEATURE_SIZE) { "Expected $FEATURE_SIZE ranking features" } }
        synchronized(batchLock) {
            val n = batch.size
            if (n > maxBatchSize) tryResizeBatch(n)

            if (maxBatchSize >= n) {
                val batchInput = batchInputCache(n)
                val batchOutput = batchOutputCache(n)
                for (i in 0 until n) {
                    System.arraycopy(batch[i], 0, batchInput[i], 0, FEATURE_SIZE)
                }
                for (i in 0 until n) batchOutput[i][0] = 0f
                try {
                    interpreter.run(batchInput, batchOutput)
                    return FloatArray(n) { batchOutput[it][0] }
                } catch (t: Throwable) {
                    Log.w(TAG, "Batched run failed; falling back to per-item", t)
                }
            }

            val out = FloatArray(n)
            for (i in 0 until n) {
                System.arraycopy(batch[i], 0, input[0], 0, FEATURE_SIZE)
                output[0][0] = 0f
                interpreter.run(input, output)
                out[i] = output[0][0]
            }
            return out
        }
    }

    private fun tryResizeBatch(targetBatch: Int) {
        if (targetBatch <= maxBatchSize) return
        try {
            interpreter.resizeInput(0, intArrayOf(targetBatch, FEATURE_SIZE))
            interpreter.resizeInput(1, intArrayOf(targetBatch, 1))
            interpreter.allocateTensors()
            maxBatchSize = targetBatch
        } catch (t: Throwable) {
            Log.w(TAG, "Batch resize to $targetBatch failed; staying at 1", t)
            try {
                interpreter.resizeInput(0, intArrayOf(1, FEATURE_SIZE))
                interpreter.resizeInput(1, intArrayOf(1, 1))
                interpreter.allocateTensors()
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to restore interpreter shape after batch resize failure", t2)
            }
            maxBatchSize = 1
        }
    }

    private var cachedBatchInput: Array<FloatArray>? = null
    private var cachedBatchOutput: Array<FloatArray>? = null

    private fun batchInputCache(n: Int): Array<FloatArray> {
        val cur = cachedBatchInput
        if (cur != null && cur.size >= n) return cur
        val new = Array(n) { FloatArray(FEATURE_SIZE) }
        cachedBatchInput = new
        return new
    }

    private fun batchOutputCache(n: Int): Array<FloatArray> {
        val cur = cachedBatchOutput
        if (cur != null && cur.size >= n) return cur
        val new = Array(n) { FloatArray(1) }
        cachedBatchOutput = new
        return new
    }

    @Synchronized
    override fun close() {
        interpreter.close()
        cachedBatchInput = null
        cachedBatchOutput = null
    }

    companion object {
        private const val TAG = "TfliteCandidateRankingScorer"
        const val FEATURE_SIZE = 83
    }
}
