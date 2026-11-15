package com.shade.keyboard.engine

interface RewriteEngine {
    val isAvailable: Boolean
    fun rewrite(text: String): String?
    fun close()
}
