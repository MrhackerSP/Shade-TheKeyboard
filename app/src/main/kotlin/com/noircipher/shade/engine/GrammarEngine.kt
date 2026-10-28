package com.noircipher.shade.engine

object GrammarEngine {
    private val CONTRACTIONS: Map<String, String> = mapOf(
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
        "im" to "I'm",
        "ive" to "I've",
        "ill" to "I'll",
        "id" to "I'd"
    )

    private val SUBJECT_NEXT: Map<String, List<String>> = mapOf(
        "i" to listOf("am", "have", "do", "can", "will", "would"),
        "he" to listOf("is", "has", "does", "can", "will", "would"),
        "she" to listOf("is", "has", "does", "can", "will", "would"),
        "it" to listOf("is", "has", "does", "can", "will", "would"),
        "we" to listOf("are", "have", "do", "can", "will", "would"),
        "you" to listOf("are", "have", "do", "can", "will", "would"),
        "they" to listOf("are", "have", "do", "can", "will", "would")
    )

    fun predictNext(contextWords: List<String>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val subject = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        return SUBJECT_NEXT[subject].orEmpty().take(limit)
    }

    fun suggest(contextWords: List<String>, typedWord: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val typed = typedWord.trim().lowercase()
        if (typed.isEmpty()) return emptyList()
        if (!typed.all { it.isLetter() || it == ''' }) return emptyList()

        val last = contextWords.lastOrNull()?.lowercase()
        val out = LinkedHashSet<String>()

        if (typed == "i") out.add("I")

        when (last) {
            "i" -> when (typed) {
                "is", "are" -> out.add("am")
                "has" -> out.add("have")
                "does" -> out.add("do")
                "dont" -> out.add("don't")
            }
            "he", "she", "it" -> when (typed) {
                "dont" -> out.add("doesn't")
                "do" -> out.add("does")
                "are" -> out.add("is")
                "have" -> out.add("has")
                "go" -> out.add("goes")
            }
            "we", "you", "they" -> when (typed) {
                "is" -> out.add("are")
                "has" -> out.add("have")
                "was" -> out.add("were")
            }
        }

        CONTRACTIONS[typed]?.let { corrected ->
            if (last in setOf("he", "she", "it") && corrected == "don't") {
                out.add("doesn't")
            } else {
                out.add(corrected)
            }
        }

        return out.take(limit)
    }
}
