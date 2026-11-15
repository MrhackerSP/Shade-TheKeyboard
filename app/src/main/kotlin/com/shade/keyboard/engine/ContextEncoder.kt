package com.shade.keyboard.engine

interface ContextEncoder {
    fun encode(wordIndex: Int): FloatArray
    fun close()
}
