package com.noircipher.shade.engine

import java.io.Closeable

interface LanguageModelEngine : Closeable {
    var enabled: Boolean
    fun predictNext(
        beforeCursor: String,
        typedPrefix: String?,
        limit: Int
    ): List<Pair<String, Float>>

    fun rerankPrefix(
        beforeCursor: String,
        typedPrefix: String,
        candidates: List<String>
    ): List<String>
}

interface EmojiPredictor {
    fun predictEmoji(typedPrefix: String, limit: Int): List<String>
}

interface DictionarySource {
    suspend fun configure(localeId: String)
}
