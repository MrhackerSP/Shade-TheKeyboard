package com.noircipher.shade

/**
 * Pure ranking logic behind the emoji panel's search bar.
 *
 * Four passes, best effort first:
 * 1. Direct — a keyword matches when it contains ALL whitespace-separated
 *    tokens of the query. Same scoring as the original release: shorter (more
 *    specific) keywords win, exact-equal or starts-with tokens are boosted,
 *    ordering stable for equal scores.
 * 2. Synonym bridge — when the direct pass found NOTHING, curated synonyms
 *    (assets/emoji/emoji_synonyms.tsv) expand the query: each query token
 *    that has a bridge entry is swapped for its canonical keywords and the
 *    direct pass reruns ("sob" finds "cry"). This is the model-free stand-in
 *    for Gboard's Gemma embedding tier. Only ever reached when the raw query
 *    matched nothing, so it can never crowd out a real match.
 * 3. Related fill — when fewer than [limit] results came back, emojis
 *    semantically close to the matched ones are appended (weight-ordered data
 *    from assets/emoji/emoji_related.tsv, mined from shared keywords).
 * 4. Fuzzy fallback — when even the bridge found NOTHING, keywords are matched
 *    token-by-token with bounded Damerau-Levenshtein tolerance (typo-proof:
 *    "moosee" finds moose, "grining face" finds grinning face). Short tokens
 *    stay exact so 3-letter queries don't drift into noise, and the summed
 *    edit distance feeds the score so the CLOSEST keyword ranks first
 *    ("lonley" lands on "lonely", not the shorter but further "honey").
 *
 * Kept free of Android types so it can be unit-tested on the JVM.
 */
object EmojiSearch {

    /** Upper bound on results returned for the results bar. */
    const val MAX_RESULTS = 48

    /** Bridge rows carry at most this many canonical keywords, best first. */
    const val SYNONYM_ALT_CAP = 4

    fun search(
        query: String,
        keywordMap: Map<String, List<String>>,
        limit: Int = MAX_RESULTS,
        relatedMap: Map<String, List<String>> = emptyMap(),
        synonymMap: Map<String, List<String>> = emptyMap()
    ): List<String> {
        if (limit <= 0) return emptyList()
        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val direct = rankMatches(tokens, keywordMap, limit, fuzzy = false)
        if (direct.isNotEmpty()) return expandRelated(direct, relatedMap, limit)

        if (synonymMap.isNotEmpty()) {
            val bridged = bridgeMatches(tokens, keywordMap, limit, synonymMap)
            if (bridged.isNotEmpty()) return expandRelated(bridged, relatedMap, limit)
        }

        val fuzzy = rankMatches(tokens, keywordMap, limit, fuzzy = true)
        if (fuzzy.isNotEmpty()) return expandRelated(fuzzy, relatedMap, limit)

        return emptyList()
    }

    /**
     * Curated-synonym fallback: replace ONE query token with each of its
     * bridge alternatives and rerun the direct pass over the full query
     * ("gloomy dog" tries "sad dog"). When nothing matches even after the
     * swaps, each alternative is tried alone so a query like "sob loudly"
     * still lands on crying emojis. Deterministic: token order, then the
     * asset's best-first alternative order.
     */
    private fun bridgeMatches(
        tokens: List<String>,
        keywordMap: Map<String, List<String>>,
        limit: Int,
        synonymMap: Map<String, List<String>>
    ): List<String> {
        val out = LinkedHashSet<String>()
        for ((i, tok) in tokens.withIndex()) {
            for (alt in synonymMap[tok].orEmpty()) {
                if (alt in tokens) continue
                val swapped = tokens.toMutableList()
                swapped[i] = alt
                for (emoji in rankMatches(swapped, keywordMap, limit, fuzzy = false)) {
                    out.add(emoji)
                    if (out.size >= limit) return out.toList()
                }
            }
        }
        for (tok in tokens) {
            for (alt in synonymMap[tok].orEmpty()) {
                if (alt in tokens) continue
                for (emoji in rankMatches(listOf(alt), keywordMap, limit, fuzzy = false)) {
                    out.add(emoji)
                    if (out.size >= limit) return out.toList()
                }
            }
        }
        return out.toList()
    }

    /** Direct and fuzzy matching share one ranking pass; only the token test differs. */
    private fun rankMatches(
        tokens: List<String>,
        keywordMap: Map<String, List<String>>,
        limit: Int,
        fuzzy: Boolean
    ): List<String> {
        data class Scored(val emoji: String, val score: Float)
        val scored = ArrayList<Scored>()

        for ((keyword, emojis) in keywordMap) {
            val kwLower = keyword.lowercase()
            val distSum: Int
            if (fuzzy) {
                distSum = tokensFuzzyDistance(tokens, kwLower) ?: continue
            } else {
                if (!tokens.all { kwLower.contains(it) }) continue
                distSum = 0
            }
            var score = keyword.length + 4f * distSum
            for (tok in tokens) {
                if (kwLower == tok) score -= 10f
                else if (kwLower.startsWith(tok)) score -= 5f
            }
            for (e in emojis) scored.add(Scored(e, score))
        }

        val results = LinkedHashSet<String>()
        for ((emoji, _) in scored.sortedBy { it.score }) {
            results.add(emoji)
            if (results.size >= limit) break
        }
        return results.toList()
    }

    /**
     * Returns the summed per-token minimal Damerau distance when EVERY query
     * token sits within typo distance of some token of the keyword, or null
     * when any token is too far. Tolerance scales with token length: 4-5 chars
     * allow one edit (incl. transposition), 6+ allow two, shorter stay exact.
     * The sum feeds ranking so the closest keyword wins.
     */
    private fun tokensFuzzyDistance(tokens: List<String>, keyword: String): Int? {
        val kwTokens = keyword.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (kwTokens.isEmpty()) return null
        var sum = 0
        for (tok in tokens) {
            val tol = when {
                tok.length <= 3 -> 0
                tok.length <= 5 -> 1
                else -> 2
            }
            var best = tol + 1
            for (kw in kwTokens) {
                val d = damerauDistance(tok, kw, tol)
                if (d < best) best = d
                if (best == 0) break
            }
            if (best > tol) return null
            sum += best
        }
        return sum
    }

    /**
     * Restricted Damerau-Levenshtein (optimal string alignment) with early exit
     * once the distance provably exceeds [max]. Pure JVM, no allocations beyond
     * the two DP rows.
     */
    private fun damerauDistance(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        val lenDiff = kotlin.math.abs(la - lb)
        if (lenDiff > max) return max + 1
        if (la == 0) return lb
        if (lb == 0) return la

        var prev2 = IntArray(lb + 1)
        var prev = IntArray(lb + 1) { it }
        var cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[lb]
    }

    /**
     * Appends weight-ordered related emojis of the already-matched emojis until
     * [limit] is reached. Related lists are pre-sorted best-first by the asset
     * generator, so insertion order is the quality order.
     */
    private fun expandRelated(
        primary: List<String>,
        relatedMap: Map<String, List<String>>,
        limit: Int
    ): List<String> {
        if (relatedMap.isEmpty() || primary.size >= limit) return primary
        val out = LinkedHashSet(primary)
        for (emoji in primary) {
            for (r in relatedMap[emoji].orEmpty()) {
                if (out.size >= limit) break
                out.add(r)
            }
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /**
     * Parses assets/emoji/emoji_synonyms.tsv ("synonym<TAB>kw1,kw2,...").
     * Keys are single lowercase words users type that the keyword corpus does
     * NOT already cover; values are at most [SYNONYM_ALT_CAP] canonical
     * keywords, best first. Pure JVM so the asset format is pinned by unit
     * tests without a device.
     */
    fun parseSynonymTsv(lines: Sequence<String>): Map<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val key = parts[0].trim().lowercase()
            if (key.isEmpty() || key.contains(' ')) continue
            val alts = parts[1].split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(SYNONYM_ALT_CAP)
            if (alts.isEmpty() || key in alts) continue
            map[key] = alts
        }
        return map
    }

    /**
     * Parses assets/emoji/emoji_related.tsv ("emoji<TAB>related<TAB>weight",
     * pre-sorted best related first). Pure JVM so the asset format is pinned by
     * unit tests without a device.
     */
    fun parseRelatedTsv(lines: Sequence<String>): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val emoji = parts[0].trim()
            val related = parts[1].trim()
            if (emoji.isNotEmpty() && related.isNotEmpty()) {
                map.getOrPut(emoji) { mutableListOf() }.add(related)
            }
        }
        return map
    }
}
