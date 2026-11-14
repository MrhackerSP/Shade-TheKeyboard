package com.noircipher.shade.engine

import android.util.Log

/**
 * #17 — Emoji context predictor.
 *
 * Suggests emojis for the previous word. Triggered when the user has just
 * typed a word and is now picking the next — we surface a relevant emoji
 * in the suggestion bar.
 *
 * Three layers:
 *  1. when [conceptScorer] is plugged in (a [TfliteConceptScorer] running the
 *     curated concept rules, optionally backed by the concept model), typed
 *     trigger words and phrases contribute emojis first;
 *  2. when [neuralScorer] is plugged in (a [TfliteExpressionScorer] running the
 *     emoji_suggester model, whose tokenizer vocabulary was recovered
 *     from its token.csym container), the model scores the last context word
 *     against its 4,522 expression queries and contributes emojis next;
 *  3. the small hardcoded word -> emoji map below fills any remaining slots
 *     (always available, zero cost).
 */
object EmojiContextPredictor {

    /** Shared with [com.noircipher.shade.emoji.EmojiPredictionEngine]; cleared on close. */
    @Volatile
    var neuralScorer: ExpressionScorer? = null

    /** Concept rules scorer, shared with [com.noircipher.shade.emoji.EmojiPredictionEngine]; cleared on close. */
    @Volatile
    var conceptScorer: ExpressionScorer? = null

    private val wordToEmoji: Map<String, List<String>> = mapOf(
        "love" to listOf("❤️", "😍", "💕"),
        "loved" to listOf("❤️", "😍"),
        "loves" to listOf("❤️", "😍"),
        "happy" to listOf("😊", "😄", "🙂"),
        "happier" to listOf("😊", "😄"),
        "happiest" to listOf("🥰", "😄"),
        "sad" to listOf("😢", "😔", "😭"),
        "angry" to listOf("😠", "😡"),
        "mad" to listOf("😠", "😡"),
        "tired" to listOf("😴", "😮‍💨"),
        "sleepy" to listOf("😴"),
        "excited" to listOf("🤩", "😆"),
        "scared" to listOf("😨", "😱"),
        "afraid" to listOf("😨"),
        "surprised" to listOf("😮", "😲"),
        "shocked" to listOf("😲", "😱"),
        "bored" to listOf("😑", "😒"),
        "cool" to listOf("😎", "🆒"),
        "funny" to listOf("😂", "🤣"),
        "fun" to listOf("🎉", "😎"),
        "lol" to listOf("😂", "🤣"),
        "lmao" to listOf("🤣", "😂"),
        "thanks" to listOf("🙏", "😊"),
        "thank" to listOf("🙏"),
        "please" to listOf("🙏"),
        "pray" to listOf("🙏"),
        "prayers" to listOf("🙏"),
        "ok" to listOf("👌", "👍"),
        "okay" to listOf("👌", "👍"),
        "yes" to listOf("👍", "✅"),
        "no" to listOf("👎", "❌"),
        "nope" to listOf("👎"),
        "yep" to listOf("👍"),
        "yeah" to listOf("👍"),
        "good" to listOf("👍", "👌"),
        "great" to listOf("🎉", "👍"),
        "awesome" to listOf("🎉", "🤩"),
        "perfect" to listOf("💯", "👌"),
        "agreed" to listOf("👍", "🤝"),
        "agree" to listOf("👍", "🤝"),
        "food" to listOf("🍕", "🍔", "🍟"),
        "hungry" to listOf("🍔", "🍕"),
        "eat" to listOf("🍽️", "🍴"),
        "dinner" to listOf("🍽️", "🍝"),
        "lunch" to listOf("🥪", "🍽️"),
        "breakfast" to listOf("🍳", "🥐"),
        "coffee" to listOf("☕", "☕️"),
        "tea" to listOf("🍵"),
        "water" to listOf("💧", "🥤"),
        "drink" to listOf("🍷", "🍺", "🍹"),
        "beer" to listOf("🍺"),
        "wine" to listOf("🍷"),
        "cake" to listOf("🎂", "🍰"),
        "party" to listOf("🎉", "🥳"),
        "celebrate" to listOf("🎉", "🎊"),
        "birthday" to listOf("🎂", "🎉"),
        "morning" to listOf("🌅", "☀️"),
        "evening" to listOf("🌆", "🌙"),
        "night" to listOf("🌙", "😴"),
        "goodnight" to listOf("🌙", "😴"),
        "sun" to listOf("☀️", "🌞"),
        "rain" to listOf("🌧️", "☔"),
        "snow" to listOf("❄️", "🌨️"),
        "hot" to listOf("🔥", "🥵"),
        "cold" to listOf("🥶", "❄️"),
        "fire" to listOf("🔥"),
        "work" to listOf("💼", "💻"),
        "meeting" to listOf("📅", "👥"),
        "phone" to listOf("📞", "📱"),
        "call" to listOf("📞"),
        "email" to listOf("📧", "✉️"),
        "mail" to listOf("📬", "📧"),
        "money" to listOf("💰", "💵"),
        "cash" to listOf("💵", "💰"),
        "dollar" to listOf("💵"),
        "time" to listOf("⏰", "🕐"),
        "clock" to listOf("🕐", "⏰"),
        "calendar" to listOf("📅", "📆"),
        "weekend" to listOf("🎉", "🛋️"),
        "vacation" to listOf("✈️", "🏖️"),
        "trip" to listOf("✈️", "🧳"),
        "flight" to listOf("✈️"),
        "car" to listOf("🚗", "🚙"),
        "drive" to listOf("🚗"),
        "walk" to listOf("🚶", "👟"),
        "run" to listOf("🏃", "🏃‍♂️"),
        "gym" to listOf("🏋️", "💪"),
        "exercise" to listOf("🏋️", "🏃"),
        "sleep" to listOf("😴", "🛏️"),
        "bed" to listOf("🛏️"),
        "home" to listOf("🏠", "🏡"),
        "house" to listOf("🏠"),
        "dog" to listOf("🐕", "🐶"),
        "cat" to listOf("🐈", "🐱"),
        "fish" to listOf("🐟", "🐠"),
        "bird" to listOf("🐦", "🦜"),
        "music" to listOf("🎵", "🎶", "🎧"),
        "song" to listOf("🎵", "🎶"),
        "movie" to listOf("🎬", "🍿", "🎞️"),
        "film" to listOf("🎬", "🎥"),
        "game" to listOf("🎮", "🎲"),
        "play" to listOf("🎮", "🎲"),
        "win" to listOf("🏆", "🥇"),
        "winner" to listOf("🏆"),
        "loss" to listOf("😔", "📉"),
        "lost" to listOf("😔"),
        "heart" to listOf("❤️", "💗", "💕"),
        "kiss" to listOf("💋", "😘"),
        "hug" to listOf("🤗", "🫂"),
        "smile" to listOf("😊", "🙂"),
        "laugh" to listOf("😂", "😆"),
        "cry" to listOf("😢", "😭"),
        "tears" to listOf("😭", "😢"),
        "sick" to listOf("🤒", "🤢"),
        "ill" to listOf("🤒"),
        "doctor" to listOf("👨‍⚕️", "🩺"),
        "hospital" to listOf("🏥"),
        "medicine" to listOf("💊"),
        "pill" to listOf("💊"),
        "gift" to listOf("🎁"),
        "present" to listOf("🎁"),
        "ring" to listOf("💍"),
        "wedding" to listOf("💍", "👰"),
        "baby" to listOf("👶", "🍼"),
        "kid" to listOf("🧒", "👶"),
        "child" to listOf("🧒"),
        "family" to listOf("👨‍👩‍👧", "👪"),
        "friend" to listOf("🤝", "👤"),
        "friends" to listOf("👥", "🤝"),
        "people" to listOf("👥", "👨‍👩‍👧"),
        "man" to listOf("👨"),
        "woman" to listOf("👩"),
        "boy" to listOf("👦"),
        "girl" to listOf("👧")
    )

    fun suggest(prevWord: String?, limit: Int = 3): List<String> {
        if (prevWord.isNullOrBlank()) return emptyList()
        val w = prevWord.trim().lowercase().trimEnd('.', ',', '!', '?')

        val out = LinkedHashSet<String>()
        conceptScorer?.takeIf { it.isAvailable }?.let { scorer ->
            try {
                out.addAll(scorer.suggestEmojis(w, limit))
            } catch (t: Throwable) {
                // Concept rules are best-effort; the layers below still apply.
                Log.w("EmojiContextPredictor", "Concept suggestion failed", t)
            }
        }
        neuralScorer?.takeIf { it.isAvailable }?.let { scorer ->
            try {
                out.addAll(scorer.suggestEmojis(w, limit))
            } catch (t: Throwable) {
                // Neural scoring is best-effort; the map below still applies.
                Log.w("EmojiContextPredictor", "Emoji suggestion failed", t)
            }
        }
        if (out.size >= limit) return out.toList().take(limit)

        out.addAll(wordToEmoji[w].orEmpty())
        return out.take(limit)
    }
}
