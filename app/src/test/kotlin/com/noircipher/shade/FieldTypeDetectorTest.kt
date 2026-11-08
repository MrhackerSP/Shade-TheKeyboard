package com.noircipher.shade

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.noircipher.shade.FieldTypeDetector.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTypeDetectorTest {
    private fun info(type: Int) = EditorInfo().also { it.inputType = type }

    @Test fun nullEditorInfoIsNormalText() {
        assertEquals(FieldType.NORMAL_TEXT, FieldTypeDetector.detect(null))
    }

    @Test fun passwordVariantsAreDetected() {
        val cls = InputType.TYPE_CLASS_TEXT
        assertEquals(FieldType.PASSWORD, FieldTypeDetector.detect(info(cls or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertEquals(FieldType.PASSWORD, FieldTypeDetector.detect(info(cls or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertEquals(FieldType.PASSWORD, FieldTypeDetector.detect(info(cls or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
    }

    @Test fun emailUrlNumberPhoneDetected() {
        val t = InputType.TYPE_CLASS_TEXT
        assertEquals(FieldType.EMAIL, FieldTypeDetector.detect(info(t or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
        assertEquals(FieldType.URL, FieldTypeDetector.detect(info(t or InputType.TYPE_TEXT_VARIATION_URI)))
        assertEquals(FieldType.NUMBER, FieldTypeDetector.detect(info(InputType.TYPE_CLASS_NUMBER)))
        assertEquals(FieldType.PHONE, FieldTypeDetector.detect(info(InputType.TYPE_CLASS_PHONE)))
    }

    @Test fun noSuggestionsFlagWins() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertEquals(FieldType.NO_SUGGESTIONS, FieldTypeDetector.detect(info(type)))
    }

    @Test fun suggestionGating() {
        val hidden = setOf(FieldType.PASSWORD, FieldType.NUMBER, FieldType.PHONE, FieldType.DATETIME, FieldType.NO_SUGGESTIONS)
        for (t in FieldType.values()) {
            assertEquals(t.name, t !in hidden, FieldTypeDetector.shouldShowSuggestions(t))
        }
    }

    @Test fun autocorrectAndSmartComposeOnlyInNormalText() {
        for (t in FieldType.values()) {
            assertEquals(t == FieldType.NORMAL_TEXT, FieldTypeDetector.shouldAutocorrect(t))
            assertEquals(t == FieldType.NORMAL_TEXT, FieldTypeDetector.shouldSmartCompose(t))
        }
        assertFalse(FieldTypeDetector.shouldAutocorrect(FieldType.PASSWORD))
        assertTrue(FieldTypeDetector.shouldAutocorrect(FieldType.NORMAL_TEXT))
    }
}
