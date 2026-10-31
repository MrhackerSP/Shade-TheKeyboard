package com.noircipher.shade

/**
 * #26 — URL / email / phone-number detector.
 *
 * Inspects the composing text + text before cursor to figure out whether
 * the user is currently typing something we should *not* autocorrect or
 * suggest completions for:
 *   - An email address (e.g. "john.doe@example")
 *   - A URL (e.g. "https://example.com" or "example.com/path")
 *   - A phone number (e.g. "+1 (555) 123-4567")
 *
 * Used to suppress autocorrect mid-token (so "@example.com" doesn't get
 * split into "@example . com" via space-insertion) and to suppress
 * next-word suggestions that would interrupt typing.
 */
object UrlEmailDetector {

    data class Detection(
        val isEmail: Boolean,
        val isUrl: Boolean,
        val isPhone: Boolean,
        val isPathSegment: Boolean
    ) {
        val anyActive: Boolean get() = isEmail || isUrl || isPhone || isPathSegment
        companion object {
            val NONE = Detection(false, false, false, false)
        }
    }

    // Email-ish: word.char@word.char — we want to allow `.` and `@` in the
    // composing text without inserting an auto-space.
    private val emailRegex = Regex("^[\\w.\\-+]+@[\\w.\\-]+\$", RegexOption.IGNORE_CASE)
    // Bare-domain + path: "example.com/path" or "sub.example.co.uk/path"
    private val urlRegex = Regex(
        "^(https?://|www\\.)?[\\w\\-]+(\\.[\\w\\-]+)+(/[^\\s]*)?\$",
        RegexOption.IGNORE_CASE
    )
    // Scheme-prefix partial — user just started typing "http" or "https://".
    private val urlPrefixRegex = Regex("^(https?://|www\\.)", RegexOption.IGNORE_CASE)
    // Phone number: leading +, parens, dashes, spaces, and digits.
    private val phoneRegex = Regex("^[+]?[\\d][\\d\\s()\\-]{2,}\$")

    fun detect(composingText: String, beforeCursor: String): Detection {
        val composing = composingText.trim()
        val before = beforeCursor.trim()
        // Combined last-token from beforeCursor + composing text — that's the
        // logical unit we're checking.
        val lastToken = lastToken(before) + composing
        if (lastToken.isEmpty()) return Detection.NONE

        // URL: scheme prefix matches even partially.
        if (urlPrefixRegex.containsMatchIn(lastToken)) {
            return Detection(isUrl = true, isEmail = false, isPhone = false, isPathSegment = false)
        }
        // Email: must contain '@'.
        if (lastToken.contains('@')) {
            // Email could be in-progress (no TLD yet). Be permissive: if it
            // has '@' and at least one character on each side, treat as email.
            val parts = lastToken.split('@', limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return Detection(isEmail = true, isUrl = false, isPhone = false, isPathSegment = false)
            }
        }
        // Phone number.
        if (phoneRegex.matches(lastToken) && lastToken.any { it.isDigit() }) {
            return Detection(isUrl = false, isEmail = false, isPhone = true, isPathSegment = false)
        }
        // URL: full regex.
        if (urlRegex.matches(lastToken)) {
            // Disambiguate: a "word.word" with no scheme/path is ambiguous —
            // could be a domain or a sentence ending in an abbreviation.
            // Only treat as URL when there's a scheme OR a path.
            if (urlPrefixRegex.containsMatchIn(lastToken) || lastToken.contains('/')) {
                return Detection(isUrl = true, isEmail = false, isPhone = false, isPathSegment = false)
            }
        }
        // Path segment: after a `/` in a URL — e.g. "example.com/foo|".
        val slashIdx = before.lastIndexOf('/')
        if (slashIdx >= 0) {
            val afterSlash = before.substring(slashIdx)
            // Only if the slash is part of a URL-looking token (no whitespace
            // between scheme/host and slash).
            val beforeSlash = before.substring(0, slashIdx)
            if (urlRegex.containsMatchIn(beforeSlash) || urlPrefixRegex.containsMatchIn(beforeSlash)) {
                val hasSpaceAfterSlash = afterSlash.any { it.isWhitespace() }
                if (!hasSpaceAfterSlash) {
                    return Detection(isUrl = false, isEmail = false, isPhone = false, isPathSegment = true)
                }
            }
        }
        return Detection.NONE
    }

    fun suppressAutocorrect(detection: Detection): Boolean {
        // Autocorrect should be suppressed when the user is actively typing
        // any URL/email/phone path. Path segments of URLs also shouldn't
        // be "corrected" to dictionary words.
        return detection.anyActive
    }

    fun suppressSuggestions(detection: Detection): Boolean {
        // Suppress suggestions when typing an email or URL; phone numbers
        // are short enough that suggestions would only get in the way.
        return detection.isEmail || detection.isUrl || detection.isPhone || detection.isPathSegment
    }

    private fun lastToken(before: String): String {
        if (before.isEmpty()) return ""
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end -= 1
        if (end == 0) return ""
        var start = end
        while (start > 0) {
            val c = before[start - 1]
            // Stop at whitespace; keep `.`, `/`, `@`, `:`, `-`, `+`, `(`, `)` so
            // we can recognize URLs and emails as a single token.
            if (c.isWhitespace()) break
            start -= 1
        }
        return before.substring(start, end)
    }
}
