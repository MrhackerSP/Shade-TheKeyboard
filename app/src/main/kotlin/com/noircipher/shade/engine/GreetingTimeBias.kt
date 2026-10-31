package com.noircipher.shade.engine

import java.util.Calendar

/**
 * #18 — Greeting / time-aware bias.
 *
 * Suggests words appropriate to the time of day (morning → coffee/commute,
 * evening → dinner/movie, etc.) when the user is starting a new sentence.
 */
object GreetingTimeBias {

    private val morningWords = listOf(
        "morning", "coffee", "breakfast", "commute", "subway", "traffic",
        "exercise", "run", "gym", "shower", "wakeup", "alarm", "early",
        "today", "schedule", "meeting", "standup", "emails"
    )
    private val afternoonWords = listOf(
        "lunch", "afternoon", "meeting", "call", "deadline", "afternoon",
        "break", "snack", "exercise", "tea", "coffee", "stretch", "deadline",
        "report", "review", "update"
    )
    private val eveningWords = listOf(
        "evening", "dinner", "movie", "watch", "show", "tv", "Netflix",
        "relax", "shower", "sleep", "tomorrow", "tonight", "tonight's",
        "plans", "home", "tired", "exhausted", "wine", "beer"
    )
    private val nightWords = listOf(
        "night", "sleep", "bed", "tomorrow", "morning", "tired", "rest",
        "goodnight", "dream", "alarm", "early", "wake"
    )

    fun timeAwareWords(prefix: String): List<String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val candidates = when (hour) {
            in 5..11 -> morningWords
            in 12..16 -> afternoonWords
            in 17..21 -> eveningWords
            else -> nightWords
        }
        if (prefix.isEmpty()) return candidates.take(3)
        val lower = prefix.lowercase()
        return candidates.filter { it.startsWith(prefix, ignoreCase = true) ||
            it.lowercase().startsWith(lower) }.take(3)
    }
}
