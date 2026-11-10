package com.noircipher.shade.engine

import com.noircipher.shade.TouchGeometry
import com.noircipher.shade.WordDictionary
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Touch-geometry-aware autocorrection engine.
 *
 * Replaces Shade's basic edit-cost correction with a weighted Damerau-Levenshtein
 * distance where each substitution cost is individually computed from:
 * - The finger's actual position on the key (if touch data is available)
 * - QWERTY adjacency (if no touch data)
 * - Phonetic similarity (sound-alike letters cost less)
 *
 * Each candidate is scored as: editCost + wordRarity - contextBonus
 * If the best candidate's score beats the keepCost, the word is corrected.
 * Otherwise it's left alone (it might be a name, slang, or URL).
 *
 * Adapted from a proven autocorrect pipeline but rewritten from scratch
 * with Shade's own data structures.
 */
class SpatialCorrector(private val dict: WordDictionary) {

    /** Per-character touch offset from key center (-0.5..0.5 in key-width units). */
    data class Touch(val dx: Float, val dy: Float)

    /** A correction candidate with its total score (lower = better). */
    data class Candidate(val word: String, val score: Float, val rank: Int)

    // ── Tunable parameters ──────────────────────────────────────────────────
    private object P {
        // Touch scatter (in key-width units)
        var sigmaX = 0.33f
        var sigmaY = 0.25f
        // Substitution costs (when no touch data)
        var subFar = 9.4f       // unrelated keys
        var subNear = 2.8f      // QWERTY-adjacent keys
        var subSound = 4.0f     // sound-alike letters (c/k, s/z, etc.)
        var subAccent = 0.9f    // accent variants (é → e)
        var subMin = 0.25f      // minimum substitution cost with touch data
        // Insertion costs
        var ins = 3.5f          // standard
        var insDouble = 2.0f    // doubled letter ("clasny" → "classny")
        var insSoft = 3.5f      // soft sign / diacritic
        // Deletion costs
        var del = 6.0f          // standard
        var delDouble = 3.4f    // doubled letter removed
        var delNeighbor = 5.0f  // adjacent key accidentally hit
        // Transposition
        var swap = 3.2f         // letter swap ("teh" → "the")
        // Language model
        var lmScale = 1.12f
        var lmK = 20f
        // Keep cost (when NOT to correct)
        var keepNormal = 9.45f
        var keepSafe = 7.8f
        var keepPerChar = 0.58f
        // Ambiguity: in safe mode, don't correct if two candidates are this close
        var ambiguousSafe = 1.0f
        // Known-word correction: only fix if the neighbor is much more likely
        var knownMargin = 3.0f
        var knownMinRank = 4000
        // Context weight
        var ctxWeight = 0.95f
        var ctxCap = 6.25f
    }

    /** Correction strength levels. */
    enum class Mode { SAFE, NORMAL }

    /**
     * Find the best correction for [typed], or null if the word should be left as-is.
     *
     * @param typed The word as typed by the user (will be lowercased internally).
     * @param touches Per-character touch offsets (empty = no touch data).
     * @param keyPositions Map of char → (centerX, centerY) for touch geometry.
     * @param prevWord The previous word (for bigram context bonus), or null.
     * @param mode SAFE (conservative) or NORMAL (aggressive).
     * @return The corrected word, or null if no correction is warranted.
     */
    fun correct(
        typed: String,
        touches: List<Touch> = emptyList(),
        keyPositions: Map<Char, Pair<Float, Float>> = emptyMap(),
        prevWord: String? = null,
        mode: Mode = Mode.SAFE
    ): String? {
        val lower = typed.lowercase()
        if (lower.length < 2) return null
        if (lower.any { !it.isLetter() && it != '\'' && it != '-' }) return null

        val known = dict.contains(lower)
        val hasTouch = touches.any { it != Touch(0f, 0f) }
        val len = lower.length

        // Short words: be extra cautious in safe mode
        if (len == 2 && (mode == Mode.SAFE || !hasTouch)) return null
        if (len == 3 && mode == Mode.SAFE && !hasTouch) return null

        // Keep cost: how "expensive" it is to say "this isn't a typo"
        var keep = keepCost(len, mode == Mode.NORMAL)
        if (len == 2) keep -= 3.5f  // 2-letter words are often typos of very common words

        // Find candidates using SymSpell + neighbor expansion
        val candidates = findCandidates(lower, touches, keyPositions, keep + 1f, prevWord, mode)
        val best = candidates.firstOrNull()

        // Try word splitting ("thankyou" → "thank you")
        val split = trySplit(lower, prevWord)
        if (split != null && split.second <= keep && (best == null || split.second < best.score)) {
            return split.first
        }

        if (best == null || best.score > keep) return null

        // In safe mode, don't correct if two candidates are nearly tied
        if (mode == Mode.SAFE) {
            val second = candidates.getOrNull(1)
            if (second != null && second.score - best.score < P.ambiguousSafe) return null
        }

        // If the typed word IS in the dictionary but rare, only correct if
        // the candidate is significantly better
        if (known) {
            val rank = dict.rankOf(lower)
            if (rank in 0 until P.knownMinRank) return null  // common word — don't touch
            val margin = if (mode == Mode.NORMAL) P.knownMargin else P.knownMargin + 1.5f
            if (keep - best.score < margin) return null
        }

        return best.word
    }

    /**
     * Weighted Damerau-Levenshtein edit distance with per-character substitution costs.
     * Supports: substitution, insertion, deletion, transposition.
     * Early exit when the cost exceeds [limit].
     */
    fun editDistance(
        typed: String,
        target: String,
        limit: Float = Float.MAX_VALUE,
        touches: List<Touch> = emptyList(),
        keyPositions: Map<Char, Pair<Float, Float>> = emptyMap()
    ): Float {
        val n = typed.length
        val m = target.length
        if (n > 30 || m > 30) return Float.MAX_VALUE

        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1)
        var cur = FloatArray(m + 1)

        // Insertion costs (per target character)
        for (j in 1..m) {
            val b = target[j - 1]
            val insCost = when {
                j >= 2 && target[j - 2] == b -> P.insDouble  // doubled letter
                isSoftLetter(b) -> P.insSoft
                else -> P.ins
            }
            prev[j] = prev[j - 1] + insCost
        }

        var prevMin = 0f
        for (i in 1..n) {
            val a = typed[i - 1]
            val touch = touches.getOrNull(i - 1)

            // Deletion costs (per typed character)
            val delCost = when {
                i >= 2 && typed[i - 2] == a -> P.delDouble
                (i >= 2 && isAdjacent(typed[i - 2], a)) || (i < n && isAdjacent(typed[i], a)) -> P.delNeighbor
                else -> P.del
            }
            cur[0] = prev[0] + delCost
            var rowMin = cur[0]

            for (j in 1..m) {
                val b = target[j - 1]
                var v = min(
                    prev[j - 1] + subCost(a, b, touch, keyPositions),
                    min(prev[j] + delCost, cur[j - 1] + insCostForPos(target, j))
                )
                // Transposition
                if (i >= 2 && j >= 2 && a == target[j - 2] && typed[i - 2] == b) {
                    v = min(v, prev2[j - 2] + P.swap)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit && prevMin > limit) return Float.MAX_VALUE
            prevMin = rowMin
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    // ── Candidate finding ────────────────────────────────────────────────────

    private fun findCandidates(
        lower: String,
        touches: List<Touch>,
        keyPositions: Map<Char, Pair<Float, Float>>,
        maxCost: Float,
        prevWord: String?,
        mode: Mode
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>()

        // Use Shade's existing SymSpell index for initial candidate set
        val ssCandidates = dict.symSpellCandidates(lower)
        for (cand in ssCandidates) {
            if (cand == lower) continue
            if (abs(cand.length - lower.length) > 2) continue
            val cost = editDistance(lower, cand, maxCost, touches, keyPositions)
            if (cost >= maxCost) continue
            val rank = dict.rankOf(cand)
            val freqCost = freqCost(rank)
            val ctxBonus = contextBonus(prevWord, cand)
            val score = cost + freqCost - ctxBonus
            candidates.add(Candidate(cand, score, rank))
        }

        // Also try plausible neighbor substitutions for 2-error typos
        if (lower.length >= 3 && touches.isNotEmpty()) {
            val expanded = expandWithNeighbors(lower, touches, keyPositions)
            for (variant in expanded) {
                if (variant == lower) continue
                val ssMore = dict.symSpellCandidates(variant)
                for (cand in ssMore) {
                    if (candidates.any { it.word == cand }) continue
                    if (abs(cand.length - lower.length) > 2) continue
                    val cost = editDistance(lower, cand, maxCost, touches, keyPositions)
                    if (cost >= maxCost) continue
                    val rank = dict.rankOf(cand)
                    val freqCost = freqCost(rank)
                    val ctxBonus = contextBonus(prevWord, cand)
                    val score = cost + freqCost - ctxBonus
                    candidates.add(Candidate(cand, score, rank))
                }
            }
        }

        return candidates.sortedBy { it.score }.take(4)
    }

    /** Generate variants of the typed word with each letter replaced by a plausible neighbor. */
    private fun expandWithNeighbors(
        lower: String,
        touches: List<Touch>,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): Set<String> {
        val out = HashSet<String>()
        val chars = lower.toCharArray()
        for (i in chars.indices) {
            val orig = chars[i]
            val neighbors = plausibleKeys(orig, touches.getOrNull(i), keyPositions)
            for (c in neighbors) {
                chars[i] = c
                out.add(String(chars))
            }
            chars[i] = orig
        }
        return out
    }

    // ── Cost functions ──────────────────────────────────────────────────────

    private fun subCost(
        a: Char, b: Char,
        touch: Touch?,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): Float {
        if (a == b) return 0f
        if (isAccentVariant(a, b)) return P.subAccent
        val sound = if (soundAlike(a, b)) P.subSound else P.subFar
        if (touch != null && keyPositions.isNotEmpty()) {
            val spatial = touchCost(a, touch, b, keyPositions) - touchCost(a, touch, a, keyPositions)
            return min(sound, spatial.coerceIn(P.subMin, P.subFar))
        }
        return min(sound, if (isAdjacent(a, b)) P.subNear else P.subFar)
    }

    private fun touchCost(
        typed: Char, touch: Touch, target: Char,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): Float {
        val p = keyPositions[typed] ?: return Float.MAX_VALUE
        val q = keyPositions[target] ?: return Float.MAX_VALUE
        val dx = (p.first + touch.dx - q.first) / P.sigmaX
        val dy = (p.second + touch.dy - q.second) / P.sigmaY
        return 0.5f * (dx * dx + dy * dy)
    }

    private fun insCostForPos(target: String, j: Int): Float {
        val b = target[j - 1]
        return when {
            j >= 2 && target[j - 2] == b -> P.insDouble
            isSoftLetter(b) -> P.insSoft
            else -> P.ins
        }
    }

    private fun keepCost(len: Int, normal: Boolean): Float =
        (if (normal) P.keepNormal else P.keepSafe) + P.keepPerChar * max(0, len - 4)

    private fun freqCost(rank: Int): Float = P.lmScale * ln(1f + rank / P.lmK)

    /**
     * Context bonus: how much more likely is [word] given [prevWord]?
     * Uses Shade's existing bigram model if available, plus user-learned bigrams.
     */
    private fun contextBonus(prevWord: String?, word: String): Float {
        if (prevWord.isNullOrEmpty()) return 0f
        val prev = prevWord.trim().lowercase()
        if (prev.isEmpty()) return 0f

        // Use Shade's bundled bigram cost
        val bigramCost = dict.bundledBigramCost(prev, word)
        var bonus = 0f
        if (bigramCost >= 0f) {
            bonus += (P.ctxWeight * (P.ctxCap - bigramCost)).coerceAtLeast(0.2f)
        }

        // Use Shade's learned bigram model
        val userPredictions = dict.bigramPredictions(prev, word)
        if (userPredictions > 0f) {
            bonus += min(2.5f, 0.9f + 0.6f * ln(1f + userPredictions))
        }

        return bonus
    }

    // ── Word splitting ──────────────────────────────────────────────────────

    private fun trySplit(lower: String, prevWord: String?): Pair<String, Float>? {
        if (lower.length < 4) return null
        var bestScore = Float.MAX_VALUE
        var bestText: String? = null
        for (k in 1 until lower.length) {
            val left = lower.substring(0, k)
            val right = lower.substring(k)
            if (left.length == 1 && !isSingleLetterWord(left)) continue
            if (right.length == 1 && !isSingleLetterWord(right)) continue
            if (!dict.contains(left) || !dict.contains(right)) continue
            val rl = dict.rankOf(left)
            val rr = dict.rankOf(right)
            if (rl < 0 || rr < 0) continue
            val score = P.subFar + freqCost(rl) + freqCost(rr) - contextBonus(prevWord, left)
            if (score < bestScore) {
                bestScore = score
                bestText = "$left $right"
            }
        }
        return bestText?.let { it to bestScore }
    }

    // ── Key adjacency and phonetic helpers ───────────────────────────────────

    private val QWERTY_ADJACENT = mapOf(
        'q' to "wa", 'w' to "qase", 'e' to "wsdr", 'r' to "edft", 't' to "rfgy",
        'y' to "tghu", 'u' to "yhji", 'i' to "ujko", 'o' to "iklp", 'p' to "ol",
        'a' to "qwsz", 's' to "awdexz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
        'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
        'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
        'n' to "bhjm", 'm' to "njk"
    )

    private fun isAdjacent(a: Char, b: Char): Boolean {
        val neighbors = QWERTY_ADJACENT[a.lowercaseChar()] ?: return false
        return b.lowercaseChar() in neighbors
    }

    private fun plausibleKeys(
        c: Char, touch: Touch?,
        keyPositions: Map<Char, Pair<Float, Float>>
    ): List<Char> {
        val neighbors = QWERTY_ADJACENT[c.lowercaseChar()] ?: return emptyList()
        return neighbors.map { it }.distinct()
    }

    private fun soundAlike(a: Char, b: Char): Boolean {
        val groups = listOf("ck", "sz", "fv", "bp", "dt", "mn", "rl")
        val la = a.lowercaseChar()
        val lb = b.lowercaseChar()
        return groups.any { la in it && lb in it && la != lb }
    }

    private fun isAccentVariant(a: Char, b: Char): Boolean {
        // Accent-stripped variants (é → e, ñ → n, etc.)
        val base = a.lowercaseChar()
        val target = b.lowercaseChar()
        val accents = mapOf(
            'à' to 'a', 'á' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a',
            'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
            'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
            'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
            'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
            'ñ' to 'n', 'ç' to 'c', 'ß' to 's', 'ý' to 'y', 'ÿ' to 'y'
        )
        return accents[base] == target || accents[target] == base
    }

    private fun isSoftLetter(c: Char): Boolean = c == 'ь' || c == 'ъ' || c == '\'' || c == '’'

    private fun isSingleLetterWord(c: String): Boolean = c.lowercase() in listOf("a", "i", "о", "а", "и", "у", "в", "к", "с")
}
