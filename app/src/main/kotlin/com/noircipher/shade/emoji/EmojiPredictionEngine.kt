package com.noircipher.shade.emoji

import android.content.Context
import com.noircipher.shade.engine.EmojiPredictor

class EmojiPredictionEngine(
    private val keywordMap: Map<String, List<String>>
) : EmojiPredictor {

    override fun predictEmoji(typedPrefix: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val key = normalize(typedPrefix)
        if (key.length < 2) return emptyList()

        val exact = keywordMap[key].orEmpty()
        if (exact.isNotEmpty()) return exact.take(limit)

        val prefixMatches = LinkedHashSet<String>()
        for ((keyword, emojis) in keywordMap) {
            if (keyword.startsWith(key)) {
                prefixMatches.addAll(emojis)
                if (prefixMatches.size >= limit) break
            }
        }
        return prefixMatches.take(limit)
    }

    private fun normalize(text: String): String =
        text.trim()
            .lowercase()
            .replace("_", " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    companion object {
        private val BUILT_IN: Map<String, List<String>> = mapOf(
            "happy" to listOf("😊", "😄", "😁"),
            "smile" to listOf("😊", "😄", "🙂"),
            "sad" to listOf("😢", "😞", "😔"),
            "cry" to listOf("😢", "😭", "😿"),
            "love" to listOf("❤️", "😍", "😘"),
            "heart" to listOf("❤️", "💕", "💖"),
            "angry" to listOf("😠", "😡", "💢"),
            "laugh" to listOf("😂", "🤣", "😆"),
            "lol" to listOf("😂", "🤣"),
            "cool" to listOf("😎", "🆒"),
            "fire" to listOf("🔥"),
            "party" to listOf("🎉", "🥳", "🎊"),
            "birthday" to listOf("🎂", "🎉", "🎈"),
            "food" to listOf("🍔", "🍕", "🍟"),
            "pizza" to listOf("🍕"),
            "coffee" to listOf("☕"),
            "tea" to listOf("🍵", "🧋"),
            "dog" to listOf("🐶", "🐕"),
            "cat" to listOf("🐱", "🐈"),
            "sun" to listOf("☀️", "🌞"),
            "moon" to listOf("🌙", "🌝"),
            "rain" to listOf("🌧️", "☔"),
            "snow" to listOf("❄️", "☃️", "🏔️"),
            "car" to listOf("🚗", "🚙", "🏎️"),
            "bus" to listOf("🚌"),
            "train" to listOf("🚆", "🚂", "🚇"),
            "plane" to listOf("✈️", "🛫", "🛬"),
            "house" to listOf("🏠", "🏡"),
            "work" to listOf("💼", "🖥️", "📊"),
            "money" to listOf("💰", "💵", "🤑"),
            "time" to listOf("⏰", "🕒"),
            "ok" to listOf("👍", "🆗", "👌"),
            "yes" to listOf("👍", "✅"),
            "no" to listOf("👎", "❌"),
            "thanks" to listOf("🙏", "😊", "💖"),
            "thank you" to listOf("🙏", "😊", "💖"),
            "sorry" to listOf("🙏", "😔"),
            "please" to listOf("🙏", "😊"),
            "good morning" to listOf("🌅", "☀️", "🙂"),
            "good night" to listOf("🌙", "😴", "🌌"),
            "hello" to listOf("👋", "🙂", "😊"),
            "hi" to listOf("👋", "🙂"),
            "bye" to listOf("👋", "😢", "✌️"),
            "congrats" to listOf("🎉", "👏", "🥳"),
            "congratulations" to listOf("🎉", "👏", "🥳"),
            "india" to listOf("🇮🇳"),
            "hindi" to listOf("🇮🇳", "🗣️"),
            "hinglish" to listOf("🇮🇳", "💬")
        )

        fun builtIn(): EmojiPredictionEngine = EmojiPredictionEngine(BUILT_IN)

        fun load(context: Context, languageTag: String = "en"): EmojiPredictor {
            val candidates = listOf(
                "languages/${languageTag}/emoji_keywords.tsv",
                "languages/${languageTag}/emoji_keywords.txt",
                "emoji/emoji_keywords.tsv",
                "emoji/emoji_keywords.txt"
            )
            for (path in candidates) {
                try {
                    val map = LinkedHashMap<String, MutableList<String>>()
                    context.assets.open(path).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val parts = line.split('	')
                            if (parts.size >= 2) {
                                val key = parts[0].trim().lowercase()
                                val emoji = parts[1].trim()
                                if (key.isNotEmpty() && emoji.isNotEmpty()) {
                                    map.getOrPut(key) { mutableListOf() }.add(emoji)
                                }
                            }
                        }
                    }
                    if (map.isNotEmpty()) return EmojiPredictionEngine(map)
                } catch (_: Exception) {
                    // Try the next asset.
                }
            }
            return builtIn()
        }
    }
}
