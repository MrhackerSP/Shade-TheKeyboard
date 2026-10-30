package com.noircipher.shade.engine

interface RewriteEngine {
    val isAvailable: Boolean
    fun rewrite(text: String): String?
    fun close()
}
