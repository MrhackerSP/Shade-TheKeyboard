package com.noircipher.shade

/**
 * Detects when the user is currently typing a URL, email address, file path,
 * hashtag, or @-mention — so the keyboard can suppress autocorrect and word
 * suggestions that would otherwise corrupt these tokens.
 *
 * The detection is purely prefix/character-based: it examines the composing
 * text and the previous committed token to decide whether the current "word"
 * should be left alone.
 *
 * Returns a [Result] describing what (if anything) was detected, so callers
 * can make per-call decisions.
 */
object UrlEmailDetector {

    enum class Kind { URL, EMAIL, PATH, HASHTAG, MENTION, NONE }

    data class Result(val kind: Kind, val suppressAutocorrect: Boolean, val suppressSuggestions: Boolean) {
        companion object {
            val NONE = Result(Kind.NONE, suppressAutocorrect = false, suppressSuggestions = false)
        }
    }

    /**
     * Examines the [composing] text (the current in-progress word) and the
     * [previousToken] (the last committed word before the cursor) to decide
     * whether autocorrect / suggestion generation should be suppressed.
     */
    fun detect(composing: String, previousToken: String? = null): Result {
        if (composing.isEmpty()) return Result.NONE

        val lower = composing.lowercase()
        val first = composing[0]

        // @-mention (e.g. social handles).
        if (first == '@') return Result(Kind.MENTION, suppressAutocorrect = true, suppressSuggestions = false)

        // #-hashtag.
        if (first == '#') return Result(Kind.HASHTAG, suppressAutocorrect = true, suppressSuggestions = false)

        // Email address — look for "name@domain" pattern.
        if (composing.contains('@')) {
            // Heuristic: there's an @ and at least one dot in the part after @.
            val atIdx = composing.indexOf('@')
            if (atIdx in 1 until composing.length - 2) {
                val domain = composing.substring(atIdx + 1)
                if (domain.contains('.') || domain.length >= 3) {
                    return Result(Kind.EMAIL, suppressAutocorrect = true, suppressSuggestions = true)
                }
            }
        }

        // URL: starts with a scheme or "www.".
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("ftp://") || lower.startsWith("www.")
        ) {
            return Result(Kind.URL, suppressAutocorrect = true, suppressSuggestions = true)
        }

        // URL: a bare domain like "example.com" — only treat as URL if the
        // previous token was a scheme/protocol-like word OR the composing has
        // a TLD-style suffix.
        val tlds = setOf("com", "org", "net", "io", "co", "us", "uk", "dev", "app", "ai")
        val dotIdx = composing.indexOf('.')
        if (dotIdx in 1 until composing.length - 2) {
            val lastPart = composing.substring(dotIdx + 1).lowercase()
            if (tlds.any { lastPart.startsWith(it) }) {
                return Result(Kind.URL, suppressAutocorrect = true, suppressSuggestions = true)
            }
        }

        // File path: starts with "/" or contains "/" in a Unix-path-like way.
        if (first == '/' || (composing.contains('/') && composing.length > 1)) {
            // Avoid false-positives like dates "12/25/2025" — only treat as
            // path when there are no spaces and at least one segment is
            // alphabetic.
            if (!composing.contains(' ') && composing.any { it.isLetter() }) {
                return Result(Kind.PATH, suppressAutocorrect = true, suppressSuggestions = true)
            }
        }

        // Windows-style path "C:\...".
        if (composing.length >= 3 && composing[1] == ':' && (composing[2] == '\\' || composing[2] == '/')) {
            return Result(Kind.PATH, suppressAutocorrect = true, suppressSuggestions = true)
        }

        return Result.NONE
    }

    /** Convenience: returns true if the composing text is any URL/email/path/etc. */
    fun isSpecialToken(composing: String): Boolean = detect(composing).kind != Kind.NONE
}
