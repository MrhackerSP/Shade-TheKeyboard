package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteEmotionScorer(
    context: Context,
    modelPath: String = "emoji_expression/qrnn_df_0.0.tflite",
    labelsPath: String = "emoji_expression/emotion_labels.tsv"
) : ExpressionScorer {

    private val labels: List<Pair<String, List<String>>> = try {
        context.assets.open(labelsPath).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('	')
                val label = parts.getOrNull(0) ?: return@mapNotNull null
                val emojis = parts.getOrNull(1)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
                if (emojis.isEmpty()) null else label to emojis
            }.toList()
        }
    } catch (_: Exception) { emptyList() }

    private val labelOrder = listOf(
        "sadness", "anger", "fear", "love", "joy", "gratefulness", "admiration",
        "amusement", "approval", "caring", "confusion", "curiosity", "desire",
        "disappointment", "disapproval", "embarrassment", "grief", "neutral",
        "optimism", "pride", "realization", "relief", "remorse"
    )

    private val interpreter: Interpreter? = try {
        val bytes = context.assets.open(modelPath).readBytes()
        Interpreter(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes))
    } catch (_: Exception) { null }

    override val isAvailable: Boolean
        get() = interpreter != null && labels.isNotEmpty()

    override fun suggestEmojis(query: String, limit: Int): List<String> {
        val itp = interpreter ?: return emptyList()
        if (query.isBlank() || limit <= 0 || !isAvailable) return emptyList()

        return try {
            val inputTensor = itp.getInputTensor(0)
            val shape = itp.getOutputTensor(0).shape()
            val outputs = Array(1) { FloatArray(shape.lastOrNull() ?: 1) }

            when {
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 1 ->
                    itp.run(arrayOf(query), outputs)
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 2 ->
                    itp.run(Array(1) { arrayOf(query) }, outputs)
                else -> return emptyList()
            }

            val scores = outputs[0]
            if (scores.isEmpty() || scores.size < labelOrder.size) return emptyList()

            val topLabels = scores
                .withIndex()
                .take(labelOrder.size)
                .filter { labelOrder.getOrNull(it.index) != "neutral" }
                .sortedByDescending { it.value }
                .take(2)
                .mapNotNull { labelOrder.getOrNull(it.index) }

            val byLabel = labels.toMap()
            val out = LinkedHashSet<String>()
            for (label in topLabels) {
                out.addAll(byLabel[label].orEmpty().take(3))
                if (out.size >= limit) break
            }
            out.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun close() {
        interpreter?.close()
    }
}
