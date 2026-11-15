package com.shade.keyboard.engine

interface ExpressionScorer {
    val isAvailable: Boolean
    fun suggestEmojis(query: String, limit: Int): List<String>
    fun close()
}
