package com.noircipher.shade.engine

import com.noircipher.shade.Key
import kotlin.math.sqrt

object RankingFeatureExtractor {
    const val FEATURE_SIZE = 83

    fun extract(
        candidate: String,
        typedPrefix: String,
        baseRankScore: Float,
        neuralScore: Float,
        spatialScore: Float,
        touches: List<FloatArray>,
        layout: Map<Char, Key>,
        contextEmbedding: FloatArray?,
        userUnigramScore: Float,
        userBigramScore: Float
    ): FloatArray {
        val f = FloatArray(FEATURE_SIZE)
        val lower = candidate.lowercase()
        val prefix = typedPrefix.lowercase()

        // Candidate summary (0..24)
        f[0] = candidate.length.toFloat()
        f[1] = candidate.count { it.isLetterOrDigit() }.toFloat()
        f[2] = baseRankScore
        f[3] = neuralScore
        f[4] = spatialScore
        f[5] = if (candidate.equals(typedPrefix, ignoreCase = true)) 1f else 0f
        f[6] = if (candidate.startsWith(typedPrefix, ignoreCase = true)) 1f else 0f
        f[7] = editDistance(lower, prefix).toFloat()
        f[8] = if (prefix.isNotEmpty()) (commonPrefixLength(lower, prefix).toFloat() / prefix.length) else 0f
        f[9] = if (prefix.isNotEmpty()) (prefix.length.toFloat() / candidate.length.coerceAtLeast(1)) else 0f
        f[10] = if (candidate.any { it.isUpperCase() }) 1f else 0f
        f[11] = if (candidate.contains('\'')) 1f else 0f
        f[12] = if (candidate.any { it.isDigit() }) 1f else 0f
        f[13] = if (candidate.contains('-') || candidate.contains('_')) 1f else 0f
        f[14] = if (candidate.contains(' ') || candidate.contains('\t')) 1f else 0f
        f[15] = vowelRatio(candidate)
        f[16] = consonantRatio(candidate)
        f[17] = userUnigramScore
        f[18] = userBigramScore
        f[19] = if (candidate.length <= 3) 1f else 0f
        f[20] = if (candidate.length in 4..7) 1f else 0f
        f[21] = if (candidate.length >= 8) 1f else 0f
        f[22] = 0f
        f[23] = 0f
        f[24] = 0f

        // Touch/spatial summary (25..34)
        val dist = touchDistances(lower, touches, layout)
        f[25] = dist.mean
        f[26] = dist.std
        f[27] = dist.min
        f[28] = dist.max
        f[29] = touches.size.toFloat()
        f[30] = if (touches.isNotEmpty()) (touches.size.toFloat() / candidate.length.coerceAtLeast(1)) else 0f
        f[31] = (touches.size - candidate.length).toFloat()
        f[32] = if (touches.isNotEmpty()) 1f else 0f
        f[33] = 0f
        f[34] = 0f

        // User/context summary (35..39)
        f[35] = if (contextEmbedding != null) 1f else 0f
        f[36] = contextEmbedding?.average()?.toFloat() ?: 0f
        f[37] = contextEmbedding?.maxOrNull() ?: 0f
        f[38] = contextEmbedding?.minOrNull() ?: 0f
        f[39] = contextEmbedding?.stddev() ?: 0f

        // Context embedding summary (40..55): 4 pooled means, maxes, mins, stddevs
        if (contextEmbedding != null && contextEmbedding.size >= 96) {
            val chunks = 4
            val size = contextEmbedding.size / chunks
            for (c in 0 until chunks) {
                val part = contextEmbedding.copyOfRange(c * size, (c + 1) * size)
                f[40 + c] = part.average().toFloat()
                f[44 + c] = part.maxOrNull() ?: 0f
                f[48 + c] = part.minOrNull() ?: 0f
                f[52 + c] = part.stddev()
            }
        }

        // Letter histogram a-z (56..81)
        for (ch in lower) {
            val idx = ch.code - 'a'.code
            if (idx in 0..25) f[56 + idx] += 1f
        }
        val denom = candidate.length.coerceAtLeast(1).toFloat()
        for (i in 56..81) f[i] /= denom

        // Bias-like final feature
        f[82] = 1f
        return f
    }

    private data class DistStats(val mean: Float, val std: Float, val min: Float, val max: Float)

    private fun touchDistances(candidate: String, touches: List<FloatArray>, layout: Map<Char, Key>): DistStats {
        if (candidate.isEmpty() || touches.isEmpty() || layout.isEmpty()) {
            return DistStats(0f, 0f, 0f, 0f)
        }
        val n = minOf(candidate.length, touches.size)
        val values = ArrayList<Float>(n)
        for (i in 0 until n) {
            val key = layout[candidate[i].lowercaseChar()] ?: continue
            val cx = (key.left + key.right) / 2f
            val cy = (key.top + key.bottom) / 2f
            val dx = touches[i][0] - cx
            val dy = touches[i][1] - cy
            values.add((dx * dx + dy * dy).sqrt())
        }
        if (values.isEmpty()) return DistStats(0f, 0f, 0f, 0f)
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return DistStats(mean, sqrt(variance), values.minOrNull() ?: 0f, values.maxOrNull() ?: 0f)
    }

    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun vowelRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        val vowels = text.count { it.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u') }
        return vowels.toFloat() / text.length
    }

    private fun consonantRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        val consonants = text.count { it.isLetter() && it.lowercaseChar() !in setOf('a', 'e', 'i', 'o', 'u') }
        return consonants.toFloat() / text.length
    }

    private fun Float.sqrt(): Float = kotlin.math.sqrt(this)

    private fun FloatArray.stddev(): Float {
        if (isEmpty()) return 0f
        val mean = average().toFloat()
        val variance = map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }
}
