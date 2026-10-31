package com.noircipher.shade.engine

/**
 * Suggests an emoji after certain "emotional" or "object" words.
 *
 * Examples:
 *   "love" → "❤️"
 *   "happy" → "😊"
 *   "sad" → "😢"
 *   "funny" → "😂"
 *   "pizza" → "🍕"
 *
 * Returns at most [limit] emojis that match the given [trigger] word, or an
 * empty list when no rule applies.
 *
 * This is a lightweight keyword table — it does NOT replace the dedicated
 * [EmojiPredictionEngine], it only provides a fast in-line hint for the
 * suggestion strip when the user just typed an emoji-worthy word.
 */
object EmojiContextPredictor {

    private val MAP: Map<String, List<String>> = mapOf(
        "love" to listOf("❤️", "😍", "💕"),
        "loved" to listOf("❤️", "😍"),
        "loves" to listOf("❤️", "😍"),
        "happy" to listOf("😊", "😄", "🙂"),
        "happier" to listOf("😊", "😄"),
        "happiest" to listOf("🤩", "😊"),
        "sad" to listOf("😢", "😞"),
        "sadder" to listOf("😢"),
        "saddest" to listOf("😭"),
        "angry" to listOf("😠", "😡"),
        "mad" to listOf("😠", "😡"),
        "excited" to listOf("🤩", "😆"),
        "exciting" to listOf("🤩", "✨"),
        "tired" to listOf("😴", "🥱"),
        "sleepy" to listOf("😴"),
        "sleep" to listOf("😴"),
        "funny" to listOf("😂", "🤣"),
        "lol" to listOf("😂", "🤣"),
        "lmao" to listOf("🤣", "😂"),
        "haha" to listOf("😂", "😆"),
        "wow" to listOf("😮", "🤯"),
        "omg" to listOf("😮", "😱"),
        "cool" to listOf("😎", "🆒"),
        "awesome" to listOf("🤩", "✨"),
        "amazing" to listOf("🤩", "🌟"),
        "great" to listOf("👍", "✨"),
        "good" to listOf("👍", "🙂"),
        "thanks" to listOf("🙏", "👍"),
        "thank" to listOf("🙏", "👍"),
        "thx" to listOf("🙏", "👍"),
        "please" to listOf("🙏"),
        "pray" to listOf("🙏"),
        "prayers" to listOf("🙏"),
        "ok" to listOf("👌", "👍"),
        "okay" to listOf("👌", "👍"),
        "yes" to listOf("✅", "👍"),
        "no" to listOf("❌", "🙅"),
        "nope" to listOf("❌"),
        "stop" to listOf("✋", "🛑"),
        "wait" to listOf("✋", "⏳"),
        "idea" to listOf("💡", "🤔"),
        "think" to listOf("🤔", "💭"),
        "thinking" to listOf("🤔", "💭"),
        "confused" to listOf("🤔", "😕"),
        "shrug" to listOf("🤷"),
        "idk" to listOf("🤷", "🤔"),
        "birthday" to listOf("🎂", "🎉"),
        "bday" to listOf("🎂", "🎉"),
        "party" to listOf("🎉", "🥳"),
        "celebrate" to listOf("🎉", "🥳"),
        "congrats" to listOf("🎉", "👏"),
        "congratulations" to listOf("🎉", "👏"),
        "clap" to listOf("👏", "🙌"),
        "applause" to listOf("👏", "🙌"),
        "wave" to listOf("👋"),
        "hi" to listOf("👋"),
        "hello" to listOf("👋"),
        "hey" to listOf("👋"),
        "bye" to listOf("👋", "👋"),
        "goodbye" to listOf("👋"),
        "pizza" to listOf("🍕"),
        "coffee" to listOf("☕", "☕️"),
        "tea" to listOf("🍵"),
        "beer" to listOf("🍺"),
        "wine" to listOf("🍷"),
        "cake" to listOf("🎂", "🍰"),
        "food" to listOf("🍔", "🍽️"),
        "eat" to listOf("🍽️", "🍴"),
        "hungry" to listOf("😋", "🍽️"),
        "thirsty" to listOf("🥤", "💧"),
        "rain" to listOf("🌧️", "☔"),
        "snow" to listOf("❄️", "☃️"),
        "sun" to listOf("☀️", "🌞"),
        "hot" to listOf("🔥", "🥵"),
        "fire" to listOf("🔥"),
        "star" to listOf("⭐", "🌟"),
        "moon" to listOf("🌙", "🌕"),
        "money" to listOf("💰", "💵"),
        "cash" to listOf("💵", "💰"),
        "time" to listOf("⏰", "⏳"),
        "clock" to listOf("🕐", "⏰"),
        "phone" to listOf("📱", "📞"),
        "call" to listOf("📞"),
        "email" to listOf("📧", "✉️"),
        "mail" to listOf("📧", "✉️"),
        "message" to listOf("💬", "📩"),
        "text" to listOf("💬", "📱"),
        "car" to listOf("🚗", "🚙"),
        "flight" to listOf("✈️", "🛫"),
        "plane" to listOf("✈️"),
        "travel" to listOf("✈️", "🌍"),
        "vacation" to listOf("🏖️", "🌴"),
        "beach" to listOf("🏖️", "🌊"),
        "music" to listOf("🎵", "🎶"),
        "song" to listOf("🎵", "🎶"),
        "dance" to listOf("💃", "🕺"),
        "sing" to listOf("🎤", "🎵"),
        "movie" to listOf("🎬", "🍿"),
        "film" to listOf("🎬"),
        "game" to listOf("🎮", "🎲"),
        "play" to listOf("▶️", "🎮"),
        "win" to listOf("🏆", "🥇"),
        "lose" to listOf("😞", "📉"),
        "lost" to listOf("❓", "🔍"),
        "found" to listOf("✅", "🔍"),
        "search" to listOf("🔍"),
        "find" to listOf("🔍"),
        "key" to listOf("🔑", "🗝️"),
        "lock" to listOf("🔒"),
        "book" to listOf("📖", "📚"),
        "read" to listOf("📖", "📚"),
        "write" to listOf("✍️", "📝"),
        "note" to listOf("📝", "🗒️"),
        "pen" to listOf("🖊️", "✒️"),
        "work" to listOf("💼", "💻"),
        "office" to listOf("🏢", "💼"),
        "meeting" to listOf("📅", "💼"),
        "schedule" to listOf("📅", "🗓️"),
        "calendar" to listOf("📅", "🗓️"),
        "date" to listOf("📅", "💖"),
        "alarm" to listOf("⏰", "🔔"),
        "warning" to listOf("⚠️", "🚨"),
        "danger" to listOf("🚨", "⚠️"),
        "stop" to listOf("🛑", "✋"),
        "go" to listOf("🚦", "▶️"),
        "wait" to listOf("⏳", "✋"),
        "fast" to listOf("⚡", "💨"),
        "slow" to listOf("🐢", "🐌"),
        "best" to listOf("💯", "⭐"),
        "perfect" to listOf("💯", "✨"),
        "100" to listOf("💯")
    )

    /**
     * Returns up to [limit] emoji suggestions for the given [trigger] word.
     * Returns an empty list when no rule applies.
     */
    fun suggest(trigger: String?, limit: Int = 1): List<String> {
        if (trigger == null || limit <= 0) return emptyList()
        val key = trigger.lowercase().trim()
        if (key.isEmpty()) return emptyList()
        return MAP[key]?.take(limit) ?: emptyList()
    }
}
