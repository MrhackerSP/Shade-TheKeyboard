package com.noircipher.shade

import android.util.Patterns

// ============================================================================
// ClipboardSuggestions — contextual clipboard surfacing.
//
// When the keyboard opens in a specific app context, this class examines the
// clipboard history and returns clips that are relevant to that context:
//
//  - Browser/URL field → URLs from the clipboard
//  - Phone/dialer field → phone numbers
//  - Email field → email addresses
//  - Maps/navigation → addresses (multi-line text, or text containing street/road/etc.)
//  - Messaging → most recent text clip (always relevant)
//  - Search → most recent text clip
//
// The suggestions are shown as chips above the keyboard (in the suggestion strip
// or as a temporary overlay). Tapping a chip pastes the clip.
//
// Mirrors Gboard's contextual clipboard feature: "if you copied an address,
// it suggests it when you open Maps."
// ============================================================================

object ClipboardSuggestions {

    /**
     * Classify a clip's content type from its text.
     *
     * @return one of [Type.URL], [Type.PHONE], [Type.EMAIL], [Type.ADDRESS],
     *   [Type.TEXT], or [Type.IMAGE] (for image clips — caller should check
     *   [Clip.isImage] first).
     */
    fun classify(text: String?): Type {
        if (text.isNullOrEmpty()) return Type.TEXT
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Type.TEXT

        // URL — http(s):// or www. or a bare domain like example.com
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return Type.URL
        if (trimmed.startsWith("www.")) return Type.URL
        // Bare domain: contains a dot, no spaces, and the part after the last dot
        // is a known TLD-ish (2-6 letters)
        if (!trimmed.contains(' ') && trimmed.contains('.')) {
            val lastDot = trimmed.lastIndexOf('.')
            val tld = trimmed.substring(lastDot + 1)
            if (tld.length in 2..6 && tld.all { it.isLetter() }) return Type.URL
        }

        // Email
        if (Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) return Type.EMAIL

        // Phone number — 7-15 digits, may contain +, spaces, dashes, parens
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length in 7..15 && trimmed.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return Type.PHONE
        }

        // Address heuristic — multi-line text containing address keywords
        if (trimmed.contains('\n')) {
            // Multi-line text — could be an address
            val lower = trimmed.lowercase()
            if (ADDRESS_KEYWORDS.any { lower.contains(it) }) return Type.ADDRESS
        }

        return Type.TEXT
    }

    /**
     * Find clips from [clips] that are relevant to the given [fieldType] and [appPackage].
     *
     * @param clips the clipboard history (most recent first)
     * @param fieldType the detected field type (from [FieldTypeDetector])
     * @param appPackage the foreground app's package name (for app-specific rules)
     * @param maxResults max number of suggestions to return (default 3)
     * @return relevant clips, most relevant first. Empty if nothing matches.
     */
    fun suggest(
        clips: List<ClipboardStore.Clip>,
        fieldType: FieldTypeDetector.FieldType,
        appPackage: String?,
        maxResults: Int = 3
    ): List<ClipboardStore.Clip> {
        if (clips.isEmpty()) return emptyList()

        // Determine which clip types are relevant for this context
        val relevantTypes = relevantTypesFor(fieldType, appPackage)
        if (relevantTypes.isEmpty()) return emptyList()

        val results = ArrayList<ClipboardStore.Clip>()
        val seenTexts = HashSet<String>()  // dedupe by text content

        for (clip in clips) {
            if (results.size >= maxResults) break

            // Image clips: only suggest in contexts where images make sense (messaging, email)
            if (clip.isImage) {
                if (Type.IMAGE in relevantTypes && clip.text !in seenTexts) {
                    results.add(clip)
                    seenTexts.add(clip.text)
                }
                continue
            }

            val type = classify(clip.text)
            if (type in relevantTypes && clip.text !in seenTexts) {
                results.add(clip)
                seenTexts.add(clip.text)
            }
        }

        return results
    }

    /**
     * Determine which clip types are relevant for a given field type + app.
     *
     * Gboard behavior this mirrors:
     * - URL field (browser address bar) → suggest URLs
     * - Phone field (dialer) → suggest phone numbers
     * - Email field → suggest emails
     * - Maps → suggest addresses
     * - Messaging → suggest recent text + images
     * - Search → suggest recent text
     */
    private fun relevantTypesFor(
        fieldType: FieldTypeDetector.FieldType,
        appPackage: String?
    ): Set<Type> {
        // App-specific overrides
        val pkg = appPackage?.lowercase().orEmpty()
        val isMaps = pkg.contains("maps") || pkg.contains("navigation") || pkg.contains("waze")
        val isDialer = pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("contacts")
        val isBrowser = pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("opera")
        val isMessaging = pkg.contains("messenger") || pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("sms") || pkg.contains("messages")
        val isEmail = pkg.contains("mail") || pkg.contains("gmail") || pkg.contains("outlook")

        // Field-type-specific (highest priority)
        return when (fieldType) {
            FieldTypeDetector.FieldType.URL -> setOf(Type.URL)
            FieldTypeDetector.FieldType.EMAIL -> setOf(Type.EMAIL)
            FieldTypeDetector.FieldType.PHONE -> setOf(Type.PHONE)
            else -> {
                // Fall back to app-package detection
                when {
                    isMaps -> setOf(Type.ADDRESS, Type.TEXT)
                    isDialer -> setOf(Type.PHONE)
                    isBrowser -> setOf(Type.URL)
                    isEmail -> setOf(Type.EMAIL, Type.TEXT, Type.IMAGE)
                    isMessaging -> setOf(Type.TEXT, Type.IMAGE)
                    else -> setOf(Type.TEXT)
                }
            }
        }
    }

    /** Clip content type for contextual suggestion matching. */
    enum class Type {
        URL,
        PHONE,
        EMAIL,
        ADDRESS,
        TEXT,
        IMAGE
    }

    /** Address keywords used by [classify] to detect address-like text. */
    private val ADDRESS_KEYWORDS = listOf(
        "street", "st", "avenue", "ave", "road", "rd", "boulevard", "blvd",
        "lane", "ln", "drive", "dr", "court", "ct", "way", "place", "pl",
        "apartment", "apt", "suite", "ste", "floor", "fl",
        "north", "south", "east", "west", "n", "s", "e", "w",
        "city", "state", "zip", "postal"
    )
}
