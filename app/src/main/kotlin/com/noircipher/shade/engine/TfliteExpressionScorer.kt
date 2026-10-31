package com.noircipher.shade.engine

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class TfliteExpressionScorer(
    context: Context,
    modelPath: String = "emoji_expression/transformer_expression.tflite",
    conceptListPath: String = "emoji_expression/concept_list.tsv"
) : ExpressionScorer {

    private data class Concept(val id: Int, val name: String, val emojis: List<String>)

    private val concepts: List<Concept> = try {
        context.assets.open(conceptListPath).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1) ?: return@mapNotNull null
                val emojis = parts.getOrNull(2)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
                if (emojis.isEmpty()) null else Concept(id, name, emojis)
            }.sortedBy { it.id }.toList()
        }
    } catch (_: Exception) { emptyList() }

    private val interpreter: Interpreter? = try {
        val modelBuffer = AssetMmap.load(context, modelPath)
        Interpreter(modelBuffer)
    } catch (_: Exception) { null }

    private val semanticIndex: Int = findOutput("semantic_output")
    private val triggerIndex: Int = findOutput("triggering_output")

    override val isAvailable: Boolean
        get() = interpreter != null && concepts.isNotEmpty() && semanticIndex >= 0

    override fun suggestEmojis(query: String, limit: Int): List<String> {
        val itp = interpreter ?: return emptyList()
        if (query.isBlank() || limit <= 0 || !isAvailable) return emptyList()

        return try {
            val inputTensor = itp.getInputTensor(0)
            val outputs = Array(itp.outputTensorCount) { idx ->
                val shape = itp.getOutputTensor(idx).shape()
                Array(1) { FloatArray(shape.lastOrNull() ?: 1) }
            }

            when {
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 1 -> {
                    itp.run(arrayOf(query), outputs)
                }
                inputTensor.dataType() == DataType.STRING && inputTensor.numDimensions() == 2 -> {
                    itp.run(Array(1) { arrayOf(query) }, outputs)
                }
                else -> return emptyList()
            }

            val semantic = outputs.getOrNull(semanticIndex)?.getOrNull(0) ?: return emptyList()
            if (semantic.size < concepts.size) return emptyList()

            val maxConceptId = concepts.maxOf { it.id }
            val top = semantic
                .withIndex()
                .filter { it.index <= maxConceptId }
                .sortedByDescending { it.value }
                .take(limit * 2)

            val out = LinkedHashSet<String>()
            for ((idx, _) in top) {
                val concept = concepts.firstOrNull { it.id == idx } ?: continue
                out.addAll(concept.emojis.take(2))
                if (out.size >= limit) break
            }
            out.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun close() {
        interpreter?.close()
    }

    private fun findOutput(name: String): Int {
        val itp = interpreter ?: return -1
        for (i in 0 until itp.outputTensorCount) {
            if (itp.getOutputTensor(i).name() == name) return i
        }
        return -1
    }
}
