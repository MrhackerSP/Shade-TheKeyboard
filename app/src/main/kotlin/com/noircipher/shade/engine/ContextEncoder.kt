package com.noircipher.shade.engine

interface ContextEncoder {
    fun encode(wordIndex: Int): FloatArray
    fun close()
}
