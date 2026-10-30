package com.noircipher.shade.engine

class CompositeExpressionScorer(
    private val scorers: List<ExpressionScorer>
) : ExpressionScorer {
    override val isAvailable: Boolean
        get() = scorers.any { it.isAvailable }

    override fun suggestEmojis(query: String, limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (scorer in scorers) {
            if (!scorer.isAvailable) continue
            out.addAll(scorer.suggestEmojis(query, limit))
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    override fun close() {
        scorers.forEach { it.close() }
    }
}
