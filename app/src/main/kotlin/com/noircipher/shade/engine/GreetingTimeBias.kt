package com.noircipher.shade.engine

import java.util.Calendar

/**
 * Time-aware greeting words.
 *
 * Given the current hour of day (or a Calendar instance), returns the most
 * likely greeting the user would type, so the suggestion bar can surface it
 * as a quick-pick at the start of a message.
 *
 *   5–11  → "good morning"
 *   12–16 → "good afternoon"
 *   17–21 → "good evening"
 *   22–4  → "good night"
 */
object GreetingTimeBias {

    fun timeAwareWords(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): List<String> {
        return when (hourOfDay) {
            in 5..11 -> listOf("good morning", "morning", "hello")
            in 12..16 -> listOf("good afternoon", "afternoon", "hi")
            in 17..21 -> listOf("good evening", "evening", "hey")
            else -> listOf("good night", "night", "hey")
        }
    }

    /**
     * When the user has just typed the first word of a greeting ("good"),
     * returns the time-appropriate completion.
     */
    fun completeGreeting(typed: String, hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String? {
        val lower = typed.lowercase().trim()
        if (lower == "good" || lower == "gud") {
            return when (hourOfDay) {
                in 5..11 -> "morning"
                in 12..16 -> "afternoon"
                in 17..21 -> "evening"
                else -> "night"
            }
        }
        return null
    }
}
