package com.noircipher.shade

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * #25 — Field-type detector.
 *
 * Classifies the active input field into a coarse bucket (NORMAL_TEXT,
 * PASSWORD, EMAIL, URL, NUMBER, PHONE, SEARCH, NO_SUGGESTIONS) so the
 * suggestion / autocorrect / smart-compose pipelines can be gated.
 *
 * Each bucket has a defined suggestion limit and behavior:
 *   - Passwords and visible-passwords: never suggest, never autocorrect,
 *     never smart-compose.
 *   - Email / URI: no autocorrect, no smart-compose, but limited
 *     suggestions are OK if the user wants to autocomplete a known
 *     address/URL.
 *   - Number / Phone: no autocorrect, no smart-compose, no word
 *     suggestions — numpad mode handles it.
 *   - Search: suggestions OK, no autocorrect, no smart-compose.
 *   - Normal text: full pipeline.
 */
object FieldTypeDetector {

    enum class FieldType {
        NORMAL_TEXT,
        PASSWORD,
        EMAIL,
        URL,
        NUMBER,
        PHONE,
        DATETIME,
        SEARCH,
        NO_SUGGESTIONS
    }

    fun detect(editorInfo: EditorInfo?): FieldType {
        if (editorInfo == null) return FieldType.NORMAL_TEXT
        val type = editorInfo.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if ((type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
            return FieldType.NO_SUGGESTIONS
        }
        return when (cls) {
            InputType.TYPE_CLASS_NUMBER ->
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldType.PASSWORD
                else FieldType.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldType.PHONE
            InputType.TYPE_CLASS_DATETIME -> FieldType.DATETIME
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldType.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldType.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldType.URL
                InputType.TYPE_TEXT_VARIATION_FILTER -> FieldType.SEARCH
                else -> FieldType.NORMAL_TEXT
            }
            else -> FieldType.NORMAL_TEXT
        }
    }

    fun shouldShowSuggestions(type: FieldType): Boolean = when (type) {
        FieldType.PASSWORD,
        FieldType.NUMBER,
        FieldType.PHONE,
        FieldType.DATETIME,
        FieldType.NO_SUGGESTIONS -> false
        FieldType.EMAIL,
        FieldType.URL,
        FieldType.SEARCH,
        FieldType.NORMAL_TEXT -> true
    }

    fun shouldAutocorrect(type: FieldType): Boolean = type == FieldType.NORMAL_TEXT

    fun shouldSmartCompose(type: FieldType): Boolean = type == FieldType.NORMAL_TEXT

    fun suggestionLimit(type: FieldType): Int = when (type) {
        FieldType.PASSWORD,
        FieldType.NUMBER,
        FieldType.PHONE,
        FieldType.DATETIME,
        FieldType.NO_SUGGESTIONS -> 0
        FieldType.EMAIL,
        FieldType.URL,
        FieldType.SEARCH -> 2
        FieldType.NORMAL_TEXT -> 3
    }
}
