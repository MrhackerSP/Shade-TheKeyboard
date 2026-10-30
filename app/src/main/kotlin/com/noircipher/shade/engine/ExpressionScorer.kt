package com.noircipher.shade.engine

interface ExpressionScorer {
    val isAvailable: Boolean
    fun suggestEmojis(query: String, limit: Int): List<String>
    fun close()
}
