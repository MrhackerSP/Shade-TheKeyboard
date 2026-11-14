package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Emoji suggestions from the bundled emotion/concept QRNN model.
 *
 * Model contract (verified against qrnn_df_0.0.tflite): one STRING [1] input, and three outputs
 * (a quantized scalar, the STRING label list, and `processed_predictions`, FLOAT32 [161]).
 * The output is therefore looked up BY NAME, and its class order comes from
 * `emotion_prediction_labels.txt` (the model's own label order), not from assumed ordinals.
 */
class TfliteEmotionScorer(
    context: Context,
    modelPath: String = "emoji_expression/qrnn_df_0.0.tflite",
    labelsPath: String = "emoji_expression/emotion_labels.tsv",
    predictionLabelsPath: String = "emoji_expression/emotion_prediction_labels.txt"
) : ExpressionScorer {

    private val labels: Map<String, List<String>> = try {
        context.assets.open(labelsPath).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                val label = parts.getOrNull(0) ?: return@mapNotNull null
                val emojis = parts.getOrNull(1)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
                if (emojis.isEmpty()) null else label to emojis
            }.toMap()
        }
    } catch (_: Exception) { emptyMap() }

    /** Class names in the order the model emits them. */
    private val predictionLabels: List<String> = try {
        context.assets.open(predictionLabelsPath).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    } catch (_: Exception) { emptyList() }

    private val interpreter: Interpreter? = try {
        val modelBuffer = AssetMmap.load(context, modelPath)
        Interpreter(modelBuffer)
    } catch (_: Exception) { null }

    /** Index of the `processed_predictions` output, or -1 if the contract doesn't hold. */
    private val predictionsIndex: Int = run {
        val itp = interpreter ?: return@run -1
        val idx = (0 until itp.outputTensorCount).firstOrNull {
            itp.getOutputTensor(it).name() == "processed_predictions"
        } ?: return@run -1
        val t = itp.getOutputTensor(idx)
        val size = t.shape().lastOrNull() ?: return@run -1
        // Contract check: float scores, one per known label. Otherwise stay disabled (visible, not silent).
        if (t.dataType() != DataType.FLOAT32 || size != predictionLabels.size) -1 else idx
    }

    override val isAvailable: Boolean
        get() = interpreter != null && labels.isNotEmpty() && predictionsIndex >= 0

    @Synchronized
    override fun suggestEmojis(query: String, limit: Int): List<String> {
        val itp = interpreter ?: return emptyList()
        if (query.isBlank() || limit <= 0 || !isAvailable) return emptyList()

        return try {
            val inputTensor = itp.getInputTensor(0)
            val scores = FloatArray(predictionLabels.size)
            val outputs = HashMap<Int, Any>().apply { put(predictionsIndex, scores) }
            val inputs: Array<Any> = when {
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 1 ->
                    arrayOf<Any>(arrayOf(query))
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 2 ->
                    arrayOf<Any>(Array(1) { arrayOf(query) })
                else -> return emptyList()
            }
            itp.runForMultipleInputsOutputs(inputs, outputs)

            val topLabels = scores
                .withIndex()
                .filter { predictionLabels[it.index] != "neutral" }
                .sortedByDescending { it.value }
                .take(2)
                .map { predictionLabels[it.index] }

            val out = LinkedHashSet<String>()
            for (label in topLabels) {
                out.addAll(labels[label].orEmpty().take(3))
                if (out.size >= limit) break
            }
            out.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
    }
}
