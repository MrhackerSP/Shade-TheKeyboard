package com.noircipher.shade.engine

object PhraseBooster {
    private val CONTINUATIONS: Map<String, List<String>> = mapOf(
        "how" to listOf("are", "do", "does", "did", "can", "could", "should", "would", "will", "is", "much", "many", "often", "long", "far"),
        "are" to listOf("you", "we", "they", "there", "these", "those", "your", "our", "their", "any"),
        "you" to listOf("are", "have", "can", "will", "do", "would", "could", "should", "know", "want", "need"),
        "what" to listOf("is", "are", "do", "does", "did", "can", "could", "should", "would", "will", "about"),
        "when" to listOf("is", "are", "do", "does", "did", "can", "could", "will", "would"),
        "where" to listOf("is", "are", "do", "does", "did", "can", "could", "will", "would"),
        "who" to listOf("is", "are", "was", "were", "will", "can", "could", "do", "does"),
        "why" to listOf("is", "are", "do", "does", "did", "can", "could", "should", "would", "not"),
        "can" to listOf("you", "we", "I", "they", "he", "she", "it"),
        "could" to listOf("you", "we", "I", "they", "he", "she", "it"),
        "should" to listOf("you", "we", "I", "they", "he", "she", "it"),
        "would" to listOf("you", "we", "I", "they", "he", "she", "it"),
        "will" to listOf("you", "we", "I", "they", "he", "she", "it"),
        "do" to listOf("you", "we", "they", "he", "she", "it", "not"),
        "does" to listOf("it", "he", "she", "this", "that"),
        "is" to listOf("it", "this", "that", "he", "she", "there", "not", "okay"),
        "i" to listOf("am", "have", "can", "will", "do", "would", "could", "should", "think", "know", "want", "need"),
        "we" to listOf("are", "have", "can", "will", "do", "would", "could", "should"),
        "they" to listOf("are", "have", "can", "will", "do", "would", "could", "should"),
        "he" to listOf("is", "has", "can", "will", "does", "would", "could"),
        "she" to listOf("is", "has", "can", "will", "does", "would", "could"),
        "it" to listOf("is", "has", "can", "will", "does", "would", "could", "not"),
        "thank" to listOf("you"),
        "thanks" to listOf("you"),
        "good" to listOf("morning", "night", "evening", "afternoon", "day", "luck", "job"),
        "please" to listOf("let", "help", "send", "call", "check"),
        "let" to listOf("me", "us", "you", "them", "him", "her"),
        "me" to listOf("know", "see", "go", "check", "help"),
        "this" to listOf("is", "was", "will", "can", "could", "should"),
        "that" to listOf("is", "was", "will", "can", "could", "should")
    )

    fun predict(previousWord: String?, limit: Int): List<String> =
        predict(previousWord, null, limit)

    fun predict(previousWord: String?, typedPrefix: String?, limit: Int): List<String> {
        if (previousWord.isNullOrBlank() || limit <= 0) return emptyList()
        val prefix = typedPrefix?.trim()?.lowercase().orEmpty()
        val all = CONTINUATIONS[previousWord.trim().lowercase()].orEmpty()
        val filtered = if (prefix.isEmpty()) all else all.filter { it.startsWith(prefix) }
        return filtered.take(limit)
    }
}
