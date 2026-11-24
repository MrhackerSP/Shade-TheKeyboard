package com.shade.keyboard

/**
 * Rule-based proofreader behind the toolbar "Proof" tool.
 *
 * Applies the same conservative machinery as the suggestion pipeline to
 * finished text instead of the word being typed:
 *
 *  - word fixes via [resolve] (bare contractions and ambiguity-guarded
 *    dictionary corrections, wired by the service) with the neural spell
 *    checker as a time-boxed fallback through [neuralResolve];
 *  - punctuation and spacing hygiene: collapse doubled spaces, drop spaces
 *    before punctuation, restore missing spaces after clause and sentence
 *    punctuation, capitalize sentence starts;
 *  - a standalone "i" is always capitalized; acronyms (all-caps) and tokens
 *    that look like URLs, paths or code (adjacent to "/@._:" and friends)
 *    are never touched; non-ASCII words (Devanagari etc.) pass through.
 *
 * Every fix is a local string operation — nothing is stored, learned or
 * transmitted. The caller applies the result in one batch edit so the app's
 * own Undo tool reverses it wholesale.
 */
class Proofreader(
    // True when the lower-cased word is a known dictionary/learned word —
    // known words are never touched by the neural fallback.
    private val isKnown: (String) -> Boolean,
    private val resolve: (String) -> String?,
    private val neuralResolve: (String) -> List<String> = { emptyList() },
    // Wall-clock budget for neural fallbacks across one proofread() call, so
    // a long draft cannot pile up dozens of TFLite decodes.
    private val neuralBudgetMs: Long = 1200L
) {

    /** Corrected text plus the number of individual fixes applied. */
    data class Result(val text: String, val fixCount: Int)

    private var neuralDeadline = 0L

    fun proofread(text: String): Result {
        if (text.isEmpty()) return Result(text, 0)
        val capped = if (text.length > MAX_INPUT) text.substring(0, MAX_INPUT) else text
        neuralDeadline = System.nanoTime() + neuralBudgetMs * 1_000_000
        var fixes = 0

        // Phase 1 — per-word fixes. Only ASCII runs count as words here:
        // digits, Devanagari, emoji and friends are separators and pass
        // through untouched.
        val words = StringBuilder(capped.length)
        var i = 0
        val n = capped.length
        while (i < n) {
            val c = capped[i]
            if (isAsciiWordChar(c)) {
                var j = i + 1
                while (j < n && isAsciiWordChar(capped[j])) j++
                // Trim apostrophes off the edges so "'tis" / "hello'" keep
                // their quotes and only the inner word is resolved.
                var start = i
                var end = j
                while (end > start && capped[end - 1] == '\'') end--
                while (start < end && capped[start] == '\'') start++
                if (start == end || isCodeToken(capped, start, end)) {
                    words.append(capped, i, j)
                } else {
                    val raw = capped.substring(start, end)
                    val fixed = fixWord(raw)
                    if (fixed != raw) fixes++
                    words.append(capped, i, start).append(fixed).append(capped, end, j)
                }
                i = j
            } else {
                words.append(c)
                i++
            }
        }

        // Phase 2 — spacing and punctuation hygiene.
        val spaced = fixSpacing(words.toString())
        fixes += spaced.second

        // Phase 3 — capitalize sentence starts.
        val out = capitalizeSentences(spaced.first)
        fixes += out.second

        return Result(out.first, fixes)
    }

    private fun fixWord(raw: String): String {
        val lower = raw.lowercase()
        // A standalone i is always "I" in English text.
        if (lower == "i") return "I"
        // All-caps runs are acronyms (NASA, FYI) — never rewrite.
        if (raw.length > 1 && raw.uppercase() == raw) return raw
        val cheap = resolve(lower)
        if (cheap != null && cheap != lower) return applyCasing(raw, cheap)
        // Known word (dictionary incl. learned + personal) — keep as typed.
        if (isKnown(lower)) return raw
        val fix = neuralFix(lower) ?: return raw
        if (fix == lower) return raw
        return applyCasing(raw, fix)
    }

    private fun neuralFix(lower: String): String? {
        if (System.nanoTime() >= neuralDeadline) return null
        val candidates = try {
            neuralResolve(lower)
        } catch (t: Throwable) {
            return null
        }
        // Accept only candidates shaped like the typed word; this runs on
        // finished text, not on the word the user is currently typing.
        return candidates.firstOrNull {
            it.length > 1 && it.length >= lower.length - 1 && it.length <= lower.length + 2
        }?.lowercase()
    }

    private fun applyCasing(raw: String, fixed: String): String = when {
        raw == raw.lowercase() -> fixed
        raw.first().isUpperCase() && raw.drop(1) == raw.drop(1).lowercase() ->
            fixed.replaceFirstChar { it.uppercaseChar() }
        else -> fixed
    }

    private fun fixSpacing(text: String): Pair<String, Int> {
        var fixes = 0
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == ' ') {
                var j = i
                while (j < n && text[j] == ' ') j++
                val next = if (j < n) text[j] else '\n'
                if (next in SPACE_BEFORE_PUNCT) {
                    fixes++
                } else {
                    if (j - i > 1) fixes++
                    sb.append(' ')
                }
                i = j
                continue
            }
            // Missing space after clause/sentence punctuation: "one,two" ->
            // "one, two", "end.Next" -> "end. Next". Sentence punctuation
            // only triggers before an upper-case letter preceded by a
            // lower-case word, so decimals ("3.5"), acronyms ("U.S.A") and
            // file names ("index.html") stay intact.
            if (i > 0 && c.isAsciiLetter()) {
                val prev = text[i - 1]
                val prevPrev = if (i > 1) text[i - 2] else '\n'
                if (prev in CLAUSE_PUNCT) {
                    sb.append(' ')
                    fixes++
                } else if (prev in SENTENCE_END && prevPrev.isAsciiLower() && c in 'A'..'Z') {
                    sb.append(' ')
                    fixes++
                }
            }
            sb.append(c)
            i++
        }
        return Pair(sb.toString(), fixes)
    }

    private fun capitalizeSentences(text: String): Pair<String, Int> {
        var fixes = 0
        val sb = StringBuilder(text)
        var capNext = true // start of the text counts as a boundary
        var i = 0
        val n = sb.length
        while (i < n) {
            val c = sb[i]
            when {
                c == '\n' -> { capNext = true; i++; continue }
                c == '.' || c == '!' || c == '?' -> {
                    // Only a sentence boundary when followed by whitespace —
                    // "index.html" and "3.5" stay untouched.
                    val next = if (i + 1 < n) sb[i + 1] else '\n'
                    capNext = next == ' ' || next == '\t' || next == '\n'
                    i++
                    continue
                }
                c == ' ' || c == '\t' -> { i++; continue }
                c in OPEN_QUOTES -> { i++; continue }
            }
            if (capNext && c in 'a'..'z') {
                sb.setCharAt(i, c.uppercaseChar())
                fixes++
            }
            capNext = false
            i++
        }
        return Pair(sb.toString(), fixes)
    }

    private fun isAsciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '\''

    private fun isCodeToken(text: String, start: Int, end: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (end < text.length) text[end] else ' '
        return (before != ' ' && before in CODE_ADJACENT) ||
            (after != ' ' && after in CODE_ADJACENT)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiLower(): Boolean = this in 'a'..'z'

    companion object {
        // Hard cap on proofread input; the Proof tool works on drafts, not books.
        internal const val MAX_INPUT = 5000
        private const val SPACE_BEFORE_PUNCT = ",.!?;:"
        private const val CLAUSE_PUNCT = ";:,"
        private const val SENTENCE_END = ".!?"
        private const val OPEN_QUOTES = "\"'“‘«([{<"
        // Tokens sitting next to these look like URLs, paths, mentions or
        // code ("https://x.com/a_1", "user@host") — left untouched.
        private const val CODE_ADJACENT = "/@._-=#?&%~+:<>[]|\\"
    }
}
