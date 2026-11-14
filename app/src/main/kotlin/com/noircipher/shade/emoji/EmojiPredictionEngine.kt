package com.noircipher.shade.emoji

import android.content.Context
import android.util.Log
import com.noircipher.shade.engine.EmojiPredictor
import com.noircipher.shade.engine.CompositeExpressionScorer
import com.noircipher.shade.engine.ExpressionScorer
import com.noircipher.shade.engine.TfliteConceptScorer
import com.noircipher.shade.engine.TfliteEmotionScorer
import com.noircipher.shade.engine.TfliteExpressionScorer

class EmojiPredictionEngine(
    private val keywordMap: Map<String, List<String>>,
    private val expressionScorer: ExpressionScorer? = null,
    /** The raw transformer scorer shared with EmojiContextPredictor, if any. */
    private val sharedExpressionScorer: ExpressionScorer? = null,
    /** The concept rules scorer shared with EmojiContextPredictor, if any. */
    private val sharedConceptScorer: ExpressionScorer? = null
) : EmojiPredictor {

    override fun predictEmoji(typedPrefix: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val key = normalize(typedPrefix)
        if (key.length < 2) return emptyList()

        val semantic = expressionScorer
            ?.takeIf { it.isAvailable && key.length >= 3 }
            ?.suggestEmojis(key, limit)
            .orEmpty()

        val exact = keywordMap[key].orEmpty()
        if (exact.isNotEmpty()) {
            return (semantic + exact).distinct().take(limit)
        }

        val prefixMatches = LinkedHashSet<String>()
        for ((keyword, emojis) in keywordMap) {
            if (keyword.startsWith(key)) {
                prefixMatches.addAll(emojis)
                if (prefixMatches.size >= limit) break
            }
        }
        val merged = (semantic + prefixMatches).distinct()
        return merged.take(limit)
    }

    override fun close() {
        // Drop the shared references first so the suggestion-bar hints (#17) never
        // touch a closed interpreter.
        if (sharedExpressionScorer != null &&
            com.noircipher.shade.engine.EmojiContextPredictor.neuralScorer === sharedExpressionScorer
        ) {
            com.noircipher.shade.engine.EmojiContextPredictor.neuralScorer = null
        }
        if (sharedConceptScorer != null &&
            com.noircipher.shade.engine.EmojiContextPredictor.conceptScorer === sharedConceptScorer
        ) {
            com.noircipher.shade.engine.EmojiContextPredictor.conceptScorer = null
        }
        expressionScorer?.close()
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

        fun builtIn(): EmojiPredictionEngine {
            com.noircipher.shade.engine.EmojiContextPredictor.neuralScorer = null
            com.noircipher.shade.engine.EmojiContextPredictor.conceptScorer = null
            return EmojiPredictionEngine(BUILT_IN)
        }

        fun load(context: Context, languageTag: String = "en"): EmojiPredictor {
            val candidates = buildList {
                val tags = if (languageTag == "hg") listOf("hg", "hi", "en") else listOf(languageTag, "en")
                for (tag in tags.distinct()) {
                    add("emoji/emoji_keywords_${tag}.tsv")
                    add("emoji/emoji_keywords_${tag}.txt")
                    add("languages/${tag}/emoji_keywords.tsv")
                    add("languages/${tag}/emoji_keywords.txt")
                }
                add("emoji/emoji_keywords_concepts.tsv")
                add("emoji/emoji_keywords.tsv")
                add("emoji/emoji_keywords.txt")
            }
            for (path in candidates) {
                try {
                    val map = LinkedHashMap<String, MutableList<String>>()
                    context.assets.open(path).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val parts = line.split('\t')
                            if (parts.size >= 2) {
                                val key = parts[0].trim().lowercase()
                                val emoji = parts[1].trim()
                                if (key.isNotEmpty() && emoji.isNotEmpty()) {
                                    map.getOrPut(key) { mutableListOf() }.add(emoji)
                                }
                            }
                        }
                    }
                    if (map.isNotEmpty()) {
                        // Merge the emoji shortcut table (emoji -> shorthand words) so
                        // shorthand like "omg"/"yikes" predicts emojis too. A missing
                        // asset is fine; the curated keywords still stand alone.
                        com.noircipher.shade.engine.AssetTsv.use(
                            context, "emoji/emoji_keywords_shortcuts.tsv",
                            "EmojiPredictionEngine", Unit,
                            failureMessage = "Failed to merge emoji keyword shortcuts"
                        ) { lines ->
                            for (line in lines) {
                                if (line.isEmpty() || line[0] == '#') continue
                                val parts = line.split('\t')
                                if (parts.size >= 2) {
                                    val key = parts[0].trim().lowercase()
                                    val emoji = parts[1].trim()
                                    if (key.isNotEmpty() && emoji.isNotEmpty()) {
                                        val list = map.getOrPut(key) { mutableListOf() }
                                        if (emoji !in list) list.add(emoji)
                                    }
                                }
                            }
                        }
                        val scorers = listOfNotNull(
                            // Concept rules first: curated triggers are the highest-precision
                            // emoji source (#26). Throwable (not Exception): a failed TFLite
                            // native load is an Error and must disable the scorer, not crash
                            // the IME process.
                            try { TfliteConceptScorer(context) } catch (t: Throwable) { com.noircipher.shade.ShadeLog.w("EmojiPredictionEngine", "concept scorer construction failed", t, once = true); null },
                            try { TfliteExpressionScorer(context) } catch (t: Throwable) { com.noircipher.shade.ShadeLog.w("EmojiPredictionEngine", "expression scorer construction failed", t, once = true); null },
                            try { TfliteEmotionScorer(context) } catch (t: Throwable) { com.noircipher.shade.ShadeLog.w("EmojiPredictionEngine", "emotion scorer construction failed", t, once = true); null }
                        )
                        // Share the concept rules scorer and the transformer expression
                        // scorer with the suggestion-bar context hints (#17); both are
                        // closed with this engine.
                        val shared = scorers.filterIsInstance<TfliteExpressionScorer>().firstOrNull()
                        com.noircipher.shade.engine.EmojiContextPredictor.neuralScorer = shared
                        val sharedConcept = scorers.filterIsInstance<TfliteConceptScorer>().firstOrNull()
                        com.noircipher.shade.engine.EmojiContextPredictor.conceptScorer = sharedConcept
                        val scorer = CompositeExpressionScorer(scorers).takeIf { it.isAvailable }
                        return EmojiPredictionEngine(map, scorer, shared, sharedConcept)
                    }
                } catch (e: Exception) {
                    // Try the next asset.
                    Log.w("EmojiPredictionEngine", "Failed to create emotion scorer", e)
                }
            }
            return builtIn()
        }
    }
}
