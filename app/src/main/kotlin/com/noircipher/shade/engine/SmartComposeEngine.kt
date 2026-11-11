package com.noircipher.shade.engine

import com.noircipher.shade.WordDictionary
import kotlin.math.ln

// ============================================================================
// SmartComposeEngine — bigram-driven multi-word phrase completion.
//
// Mirrors Gboard's Smart Compose: after typing "I'll", suggests "I'll call you
// back" or "I'll let you know". After "good", suggests "good morning" or
// "good to see you".
//
// Architecture:
//  Greedy beam search over the bundled bigram model (en_bi.txt, ~400k entries).
//  Starting from the last 1-2 words before the cursor, the engine predicts the
//  most likely next word, appends it, predicts again, and repeats — up to
//  [maxWords] words. Each candidate phrase is scored by the sum of bigram
//  costs (lower = more likely). The top [limit] phrases are returned.
//
//  Unlike the old PhraseCompletion (94-entry hardcoded map), this engine
//  generates phrases dynamically from the real bigram data, so it covers
//  thousands of word combinations, not just the 94 that were hand-coded.
//
//  The engine also merges with SmartResponseComposer templates (chat-reply
//  patterns like "ok" → "ok sounds good") as a boost layer, since bigrams
//  alone don't produce those well.
// ============================================================================

object SmartComposeEngine {

    /**
     * Suggest multi-word phrase completions for the current context.
     *
     * @param dict the word dictionary (provides bigram model + bundled bigrams)
     * @param contextWords words before the cursor, oldest first (lowercase).
     *   E.g. ["i", "will"] if the user typed "i will ".
     * @param typedPrefix the word the user is currently typing (may be empty
     *   for next-word prediction after a space). E.g. "cal" if typing "i will cal".
     * @param maxWords maximum number of words to chain (default 5, matching Gboard).
     * @param limit maximum number of phrase suggestions to return (default 3).
     * @return list of phrase strings (lowercase), best first. Each phrase
     *   starts with the typed prefix (if non-empty) and chains 1-4 more words.
     *   E.g. ["call you back", "call you", "call me"].
     */
    fun suggest(
        dict: WordDictionary,
        contextWords: List<String>,
        typedPrefix: String,
        maxWords: Int = 5,
        limit: Int = 3
    ): List<String> {
        if (contextWords.isEmpty() && typedPrefix.isEmpty()) return emptyList()

        val prev1 = contextWords.lastOrNull()?.lowercase()
        val prev2 = contextWords.dropLast(1).lastOrNull()?.lowercase()

        // If the user is mid-word (typedPrefix non-empty), the first word of the
        // phrase must start with typedPrefix. Get candidates from the dictionary's
        // prefix completion + bigram reranking.
        val firstWordCandidates: List<String> = if (typedPrefix.isNotEmpty()) {
            // Get prefix matches, reranked by bigram context
            val raw = dict.suggest(typedPrefix, 12)
            if (raw.isEmpty()) return emptyList()
            dict.rerankWithContext(prev2, prev1, raw).ifEmpty { raw }
        } else {
            // Next-word prediction (no prefix): use bigram model directly
            dict.predictNext(prev2, prev1, 10).ifEmpty {
                // Fallback: try heuristic phrase suggestions
                HeuristicSuggestionEngine.phrase(prev1, 8)
            }
        }

        if (firstWordCandidates.isEmpty()) return emptyList()

        // For each first-word candidate, chain forward greedily.
        // We explore the top-K first words, chain each, then pick the best phrases.
        val phrases = ArrayList<ScoredPhrase>()
        val exploreK = minOf(firstWordCandidates.size, 5)  // beam width

        for (i in 0 until exploreK) {
            val firstWord = firstWordCandidates[i]
            // Skip words that contain non-letters (punctuation, digits) — Smart Compose
            // is for natural-language phrases, not codes or numbers.
            if (!firstWord.all { it.isLetter() || it == '\'' || it == '-' }) continue

            // Score the first word's bigram cost
            val firstCost = bigramCost(dict, prev1, firstWord)
            if (firstCost > MAX_COST) continue  // too unlikely, skip

            // Chain forward from firstWord
            val chain = chainForward(dict, prev1, firstWord, firstCost, maxWords - 1)
            if (chain.isNotEmpty()) {
                phrases.add(chain)
            }
        }

        // Also add chat-response templates as a boost layer (ok → "ok sounds good")
        val templatePhrases = SmartResponseComposer.suggest(typedPrefix, limit)
        for (tpl in templatePhrases) {
            // Score templates by their first bigram cost (rough)
            val words = tpl.split(' ')
            if (words.size >= 2) {
                val cost = bigramCost(dict, words[0], words[1])
                phrases.add(ScoredPhrase(tpl, cost))
            } else if (words.size == 1) {
                phrases.add(ScoredPhrase(tpl, bigramCost(dict, prev1, words[0])))
            }
        }

        if (phrases.isEmpty()) return emptyList()

        // Sort by total cost (lower = better), deduplicate, take top [limit]
        return phrases
            .sortedBy { it.cost }
            .map { it.phrase }
            .distinct()
            .take(limit)
    }

    /**
     * Greedily chain words forward from [firstWord] using the bigram model.
     * Returns the best phrase found starting with [firstWord].
     */
    private fun chainForward(
        dict: WordDictionary,
        prevBeforeFirst: String?,
        firstWord: String,
        firstCost: Float,
        remainingWords: Int
    ): ScoredPhrase {
        val words = ArrayList<String>()
        words.add(firstWord)
        var totalCost = firstCost
        var prevWord = firstWord
        var prevPrev = prevBeforeFirst

        while (words.size <= remainingWords) {
            // Predict the next word given the chain so far
            val candidates = dict.predictNext(prevPrev, prevWord, 6)
            if (candidates.isEmpty()) break

            // Pick the best candidate (lowest bigram cost)
            var bestWord: String? = null
            var bestCost = Float.MAX_VALUE
            for (cand in candidates) {
                // Skip non-letter words (punctuation, numbers)
                if (!cand.all { it.isLetter() || it == '\'' || it == '-' }) continue
                // Skip repetitions ("the the") — never useful in a phrase
                if (cand == prevWord) continue
                // Skip very common stopwords that make phrases boring ("the the and")
                if (words.size >= 2 && cand in BORING_STOPWORDS && prevWord in BORING_STOPWORDS) continue

                val cost = bigramCost(dict, prevWord, cand)
                if (cost < bestCost) {
                    bestCost = cost
                    bestWord = cand
                }
            }

            if (bestWord == null || bestCost > MAX_COST) break

            // Stop if the phrase is getting too unlikely
            if (totalCost + bestCost > MAX_TOTAL_COST) break

            words.add(bestWord)
            totalCost += bestCost
            prevPrev = prevWord
            prevWord = bestWord
        }

        // Only return phrases with at least 2 words (1 word isn't a "phrase")
        if (words.size < 2) return ScoredPhrase("", Float.MAX_VALUE)

        return ScoredPhrase(words.joinToString(" "), totalCost)
    }

    /**
     * Get the bigram cost of (prev → word). Lower = more likely.
     * Uses the bundled bigram model first, falls back to the learned model.
     */
    private fun bigramCost(dict: WordDictionary, prev: String?, word: String): Float {
        if (prev == null) return DEFAULT_COST
        // Try bundled bigrams first (has ~400k entries, fast lookup)
        val bundledCost = dict.bundledBigramCost(prev, word)
        if (bundledCost >= 0f) return bundledCost
        // Fallback: learned bigram model
        val learnedScore = dict.bigramPredictions(prev, word)
        return if (learnedScore > 0f) -ln(learnedScore) else DEFAULT_COST
    }

    /** A phrase with its total bigram cost (lower = better). */
    private data class ScoredPhrase(val phrase: String, val cost: Float)

    companion object {
        /** Maximum bigram cost for a word to be included (weights in en_bi.txt are 0-14, stored as /2). */
        private const val MAX_COST = 8.0f

        /** Maximum total cost for a multi-word phrase. */
        private const val MAX_TOTAL_COST = 20.0f

        /** Default cost when no bigram data is available. */
        private const val DEFAULT_COST = 6.0f

        /**
         * Very common stopwords that make phrases boring when chained together.
         * E.g. "the and of" is not a useful suggestion. We allow individual
         * stopwords but block chains of two or more.
         */
        private val BORING_STOPWORDS = setOf(
            "the", "a", "an", "of", "to", "in", "on", "at", "by", "for",
            "and", "or", "but", "is", "was", "are", "were", "be", "been"
        )
    }
}
