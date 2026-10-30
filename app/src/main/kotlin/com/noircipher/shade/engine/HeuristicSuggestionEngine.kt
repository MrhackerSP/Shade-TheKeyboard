package com.noircipher.shade.engine

import java.util.Calendar

internal object PhraseSuggestions {
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

internal object TrigramSuggestions {
    private val CONTINUATIONS: Map<String, List<String>> = mapOf(
        "how are you" to listOf("doing", "okay", "good", "feeling"),
        "how do you" to listOf("do", "feel", "know", "like"),
        "what are you" to listOf("doing", "talking", "looking", "saying"),
        "what do you" to listOf("want", "mean", "think", "do"),
        "where are you" to listOf("going", "now", "from"),
        "can you please" to listOf("send", "help", "call", "check"),
        "could you please" to listOf("send", "help", "call", "check"),
        "thank you very" to listOf("much"),
        "thank you so" to listOf("much"),
        "see you later" to listOf("bye", "then"),
        "i want to" to listOf("go", "see", "buy", "eat", "know", "talk"),
        "i need to" to listOf("go", "know", "talk", "buy", "do"),
        "i have to" to listOf("go", "do", "say", "tell"),
        "i would like" to listOf("to", "some", "a"),
        "it would be" to listOf("great", "nice", "good", "better"),
        "let me know" to listOf("when", "if", "please"),
        "i am going" to listOf("to", "home", "now"),
        "we are going" to listOf("to", "home", "now"),
        "are you coming" to listOf("today", "tomorrow", "now"),
        "do you want" to listOf("to", "some", "me"),
        "did you see" to listOf("it", "that", "this"),
        "i will be" to listOf("there", "back", "home", "there"),
        "this is a" to listOf("good", "great", "test", "new"),
        "that is a" to listOf("good", "great", "nice", "big")
    )

    fun predict(contextWords: List<String>, typedPrefix: String?, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val key = contextWords.takeLast(3).joinToString(" ").trim().lowercase()
        val all = CONTINUATIONS[key].orEmpty()
        val prefix = typedPrefix?.trim()?.lowercase().orEmpty()
        val filtered = if (prefix.isEmpty()) all else all.filter { it.startsWith(prefix) }
        return filtered.take(limit)
    }
}

internal object SmartSuggestions {
    private val QUESTION_CONTINUATIONS: Map<String, List<String>> = mapOf(
        "what" to listOf("is", "are", "do", "does", "happened", "about", "kind"),
        "where" to listOf("is", "are", "did", "do", "should", "can"),
        "when" to listOf("is", "are", "will", "did", "do", "can"),
        "why" to listOf("is", "are", "did", "do", "not", "would"),
        "who" to listOf("is", "are", "was", "were", "will"),
        "how" to listOf("are", "do", "does", "did", "can", "could", "much", "many")
    )

    private val COLLOCATIONS: Map<String, List<String>> = mapOf(
        "good" to listOf("morning", "night", "luck", "job", "idea"),
        "see" to listOf("you", "this", "that", "it"),
        "talk" to listOf("to", "about", "with", "later"),
        "let" to listOf("me", "us", "you", "them", "him", "her"),
        "want" to listOf("to", "some", "me", "you", "it"),
        "need" to listOf("to", "some", "help", "it", "you"),
        "about" to listOf("to", "it", "this", "that", "you"),
        "thank" to listOf("you"),
        "sorry" to listOf("for", "about", "to", "if"),
        "love" to listOf("you", "it", "this", "that"),
        "miss" to listOf("you", "it", "this"),
        "bye" to listOf("bye", "see", "take", "good"),
        "hello" to listOf("there", "how", "hi"),
        "hi" to listOf("there", "how", "hello"),
        "please" to listOf("let", "help", "send", "call", "check"),
        "okay" to listOf("okay", "then", "sure", "thanks"),
        "ok" to listOf("ok", "okay", "then", "sure"),
        "sure" to listOf("thing", "about", "let", "no"),
        "wait" to listOf("for", "a", "what", "please"),
        "come" to listOf("on", "back", "here", "to"),
        "hold" to listOf("on", "up", "it"),
        "take" to listOf("care", "it", "this", "that", "me"),
        "make" to listOf("sure", "it", "this", "that"),
        "look" to listOf("at", "like", "for", "into"),
        "look forward" to listOf("to"),
        "looking forward" to listOf("to")
    )

    private val INTENT_CONTINUATIONS: Map<String, List<String>> = mapOf(
        "thank" to listOf("you", "u"),
        "thanks" to listOf("a", "lot", "again"),
        "sorry" to listOf("for", "about", "to", "if"),
        "congratulations" to listOf("on", "to", "and"),
        "congrats" to listOf("on", "to", "and"),
        "hello" to listOf("there", "how", "hi"),
        "hi" to listOf("there", "how", "hello"),
        "bye" to listOf("see", "take", "good"),
        "goodbye" to listOf("see", "take", "good"),
        "love" to listOf("you", "it"),
        "miss" to listOf("you", "it"),
        "help" to listOf("me", "you", "with", "please"),
        "call" to listOf("me", "you", "back", "please"),
        "send" to listOf("me", "you", "it", "this", "that"),
        "check" to listOf("it", "this", "that", "out")
    )

    private val STEM_COMPLETIONS: Map<String, List<String>> = mapOf(
        "congratul" to listOf("congratulations", "congratulate"),
        "inter" to listOf("interesting", "interested", "internet", "internal"),
        "info" to listOf("information", "inform", "informal"),
        "app" to listOf("apple", "application", "appointment", "apply"),
        "ach" to listOf("achieve", "achievement", "achieved"),
        "env" to listOf("environment", "envy"),
        "gov" to listOf("government", "governor"),
        "educ" to listOf("education", "educate", "educational"),
        "comm" to listOf("communication", "community", "company", "common"),
        "recom" to listOf("recommend", "recommendation"),
        "meet" to listOf("meeting", "meet"),
        "travel" to listOf("traveling", "travelled", "traveler"),
        "program" to listOf("programming", "programmer", "programmed"),
        "develop" to listOf("development", "developer", "developed"),
        "manage" to listOf("management", "manager", "managed"),
        "enjoy" to listOf("enjoying", "enjoyed", "enjoyment"),
        "study" to listOf("studying", "studied", "student", "studies")
    )

    private val CODE_SWITCH: List<String> = listOf(
        "hai", "kya", "nahi", "theek", "acha", "achha", "please", "okay", "thanks", "bhai"
    )

    fun suggest(contextWords: List<String>, typedPrefix: String?, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val prefix = typedPrefix?.trim()?.lowercase().orEmpty()
        val out = LinkedHashSet<String>()

        out.addAll(timeGreeting())
        out.addAll(intentContinuations(contextWords))
        out.addAll(questionContinuations(contextWords))
        out.addAll(collocationContinuations(contextWords))
        out.addAll(codeSwitchContinuations(contextWords))
        out.addAll(inflectionContinuations(contextWords, prefix.isEmpty()))
        out.addAll(stemCompletions(typedPrefix))

        val filtered = if (prefix.isEmpty()) out.toList() else out.filter { it.startsWith(prefix, ignoreCase = true) }
        return filtered.take(limit)
    }

    private fun questionContinuations(contextWords: List<String>): List<String> =
        QUESTION_CONTINUATIONS[contextWords.lastOrNull()?.lowercase()].orEmpty()

    private fun collocationContinuations(contextWords: List<String>): List<String> {
        val last = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        val lastTwo = contextWords.takeLast(2).joinToString(" ").lowercase()
        return COLLOCATIONS[lastTwo].orEmpty() + COLLOCATIONS[last].orEmpty()
    }

    private fun intentContinuations(contextWords: List<String>): List<String> {
        val last = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        return INTENT_CONTINUATIONS[last].orEmpty()
    }

    private fun timeGreeting(): List<String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 4..11 -> listOf("morning")
            in 12..16 -> listOf("afternoon")
            in 17..21 -> listOf("evening")
            else -> listOf("night")
        }
    }

    private fun stemCompletions(typedPrefix: String?): List<String> {
        val p = typedPrefix?.lowercase().orEmpty()
        if (p.length < 3) return emptyList()
        return STEM_COMPLETIONS.entries
            .filter { it.key.startsWith(p) || p.startsWith(it.key) }
            .flatMap { it.value }
    }

    private fun inflectionContinuations(contextWords: List<String>, nextWord: Boolean): List<String> {
        if (!nextWord) return emptyList()
        val last = contextWords.lastOrNull()?.lowercase() ?: return emptyList()
        return when (last) {
            "party" -> listOf("parties")
            "go" -> listOf("going", "gone", "goes")
            "have" -> listOf("has", "had", "having")
            "do" -> listOf("does", "did", "doing")
            "try" -> listOf("tries", "tried", "trying")
            "study" -> listOf("studies", "studied", "studying", "student")
            "carry" -> listOf("carries", "carried", "carrying")
            "make" -> listOf("makes", "made", "making")
            "take" -> listOf("takes", "took", "taken", "taking")
            "write" -> listOf("writes", "wrote", "written", "writing")
            "read" -> listOf("reads", "reading")
            "eat" -> listOf("eats", "ate", "eaten", "eating")
            "run" -> listOf("runs", "ran", "running")
            else -> emptyList()
        }
    }

    private fun codeSwitchContinuations(contextWords: List<String>): List<String> {
        if (contextWords.size < 2) return emptyList()
        val hasLatin = contextWords.any { word -> word.any { it.isLetter() && it.code < 128 } }
        val hasDevanagari = contextWords.any { word -> word.any { it in '\u0900'..'\u097F' } }
        return if (hasLatin && hasDevanagari) CODE_SWITCH else emptyList()
    }
}

object HeuristicSuggestionEngine {
    fun phrase(previousWord: String?, limit: Int): List<String> =
        PhraseSuggestions.predict(previousWord, limit)

    fun phrase(previousWord: String?, typedPrefix: String?, limit: Int): List<String> =
        PhraseSuggestions.predict(previousWord, typedPrefix, limit)

    fun trigram(contextWords: List<String>, typedPrefix: String?, limit: Int): List<String> =
        TrigramSuggestions.predict(contextWords, typedPrefix, limit)

    fun smart(contextWords: List<String>, typedPrefix: String?, limit: Int): List<String> =
        SmartSuggestions.suggest(contextWords, typedPrefix, limit)
}
