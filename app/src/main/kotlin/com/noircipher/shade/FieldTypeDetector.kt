package com.noircipher.shade

import android.view.inputmethod.EditorInfo
import android.text.InputType

/**
 * Detects the logical "field type" from an [EditorInfo] so the keyboard can
 * choose appropriate behaviour (e.g. suppress suggestions in code fields,
 * bias toward @-mentions in social apps, treat search fields specially).
 *
 * Returns a [FieldType] enum that the rest of the keyboard can switch on.
 *
 * This is purely heuristic — Android's InputType system doesn't natively
 * distinguish "code" from "prose", for example, so we use combinations of
 * the variation, hints, and package name to make a best guess.
 */
object FieldTypeDetector {

    enum class FieldType {
        TEXT,           // generic free-form text
        EMAIL,          // email address
        URL,            // web URL
        PHONE,          // phone number
        NUMBER,         // numeric
        DATE,           // date / time
        SEARCH,         // search box (filter suggestions)
        PASSWORD,       // password / OTP — no learning
        CODE,           // programming code — special autocorrect
        MENTION,        // social media @-mention or #-hashtag field
        MESSAGE,        // chat / SMS (looks at packageName)
        ADDRESS,        // postal address
        UNKNOWN
    }

    fun detect(editorInfo: EditorInfo?): FieldType {
        if (editorInfo == null) return FieldType.UNKNOWN
        val type = editorInfo.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val flags = type and InputType.TYPE_MASK_FLAGS

        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return when (variation) {
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> FieldType.PASSWORD
                else -> FieldType.NUMBER
            }
        }
        if (cls == InputType.TYPE_CLASS_PHONE) return FieldType.PHONE
        if (cls == InputType.TYPE_CLASS_DATETIME) return FieldType.DATE

        if (cls != InputType.TYPE_CLASS_TEXT) return FieldType.UNKNOWN

        // Password-family variations always block learning.
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) return FieldType.PASSWORD

        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) return FieldType.EMAIL

        if (variation == InputType.TYPE_TEXT_VARIATION_URI) return FieldType.URL

        if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) return FieldType.SEARCH

        if (variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS) return FieldType.ADDRESS

        if (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
            // The app explicitly opted out of suggestions — often a code editor
            // or terminal-style field.  Treat it as CODE for autocorrect purposes.
            return FieldType.CODE
        }

        // Heuristic: package-name based detection for messaging apps.
        val pkg = editorInfo.packageName?.lowercase().orEmpty()
        val isMessagingApp = pkg.contains("messenger") || pkg.contains("whatsapp") ||
            pkg.contains("telegram") || pkg.contains("sms") || pkg.contains("message") ||
            pkg.contains("discord") || pkg.contains("slack") || pkg.contains("signal")
        if (isMessagingApp) return FieldType.MESSAGE

        return FieldType.TEXT
    }

    /** True when the field type supports word suggestions (most do). */
    fun shouldShowSuggestions(type: FieldType): Boolean = when (type) {
        FieldType.TEXT, FieldType.MESSAGE, FieldType.EMAIL, FieldType.URL,
        FieldType.SEARCH, FieldType.CODE, FieldType.MENTION, FieldType.ADDRESS -> true
        FieldType.PASSWORD, FieldType.NUMBER, FieldType.PHONE, FieldType.DATE,
        FieldType.UNKNOWN -> false
    }

    /** True when autocorrect should be active. Off in code, password, URL fields. */
    fun shouldAutocorrect(type: FieldType): Boolean = when (type) {
        FieldType.TEXT, FieldType.MESSAGE -> true
        else -> false
    }
}
