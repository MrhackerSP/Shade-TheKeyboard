package com.noircipher.shade.engine

import android.content.Context

/**
 * Offline rewrite backend.
 *
 * The bundled encoder/decoder package is available for full neural rewriting,
 * but live typing uses conservative deterministic rewrite rules first. This
 * keeps latency low and avoids surprising rewrites while the heavier model
 * remains available for future sentence-level rewriting.
 */
class TfliteRewriteEngine(@Suppress("UNUSED_PARAMETER") context: Context) : RewriteEngine {

    override val isAvailable: Boolean = true

    private val normalizations: Map<String, String> = mapOf(
        "cant" to "can't",
        "dont" to "don't",
        "wont" to "won't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "doesnt" to "doesn't",
        "didnt" to "didn't",
        "couldnt" to "couldn't",
        "shouldnt" to "shouldn't",
        "wouldnt" to "wouldn't",
        "aint" to "isn't",
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "kinda" to "kind of",
        "sorta" to "sort of",
        "dunno" to "do not know",
        "cmon" to "come on",
        "lemme" to "let me",
        "gimme" to "give me",
        "u" to "you",
        "ur" to "your",
        "pls" to "please",
        "plz" to "please",
        "thx" to "thanks",
        "luv" to "love"
    )

    override fun rewrite(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > 48) return null

        val lower = trimmed.lowercase()
        val mapped = normalizations[lower] ?: return null

        return when {
            trimmed.all { it.isLowerCase() } -> mapped
            trimmed.firstOrNull()?.isUpperCase() == true && trimmed.drop(1).all { it.isLowerCase() } ->
                mapped.replaceFirstChar { it.uppercase() }
            else -> mapped
        }
    }

    override fun close() {
        // No live interpreter is held for the lightweight rewrite path.
    }
}
