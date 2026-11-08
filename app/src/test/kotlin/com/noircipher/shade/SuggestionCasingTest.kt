package com.noircipher.shade

import com.noircipher.shade.SuggestionCasing.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionCasingTest {
    @Test fun symbolPadNeverRecases() {
        assertEquals(Style.NONE, SuggestionCasing.styleFor(false, 2, "abc"))
    }

    @Test fun capsLockIsUpper() {
        assertEquals(Style.UPPER, SuggestionCasing.styleFor(true, 2, ""))
        assertEquals(Style.UPPER, SuggestionCasing.styleFor(true, 2, "x"))
    }

    @Test fun typingInCapitalsKeepsUpper() {
        assertEquals(Style.UPPER, SuggestionCasing.styleFor(true, 0, "OK"))
        assertEquals(Style.UPPER, SuggestionCasing.styleFor(true, 0, "NASA"))
    }

    @Test fun singleCapitalLetterIsTitleNotUpper() {
        assertEquals(Style.TITLE, SuggestionCasing.styleFor(true, 0, "O"))
    }

    @Test fun capitalisedWordGivesTitle_lowercaseGivesNone() {
        assertEquals(Style.TITLE, SuggestionCasing.styleFor(true, 0, "Hel"))
        assertEquals(Style.NONE, SuggestionCasing.styleFor(true, 0, "hel"))
        assertEquals(Style.NONE, SuggestionCasing.styleFor(true, 1, "hel"))
    }

    @Test fun nothingTypedFollowsShift() {
        assertEquals(Style.TITLE, SuggestionCasing.styleFor(true, 1, ""))
        assertEquals(Style.NONE, SuggestionCasing.styleFor(true, 0, ""))
    }

    @Test fun applyRecasesLetters() {
        assertEquals("Hello", SuggestionCasing.apply("hello", Style.TITLE))
        assertEquals("HELLO", SuggestionCasing.apply("hello", Style.UPPER))
        assertEquals("hello", SuggestionCasing.apply("hello", Style.NONE))
        assertEquals("", SuggestionCasing.apply("", Style.UPPER))
    }

    @Test fun applyLeavesEmojiNumbersAndPunctuationAlone() {
        assertEquals("😀", SuggestionCasing.apply("😀", Style.UPPER))
        assertEquals("123", SuggestionCasing.apply("123", Style.TITLE))
        assertEquals("?", SuggestionCasing.apply("?", Style.UPPER))
    }

    @Test fun protectedClipboardTextIsNeverRecased() {
        assertEquals("secretToken", SuggestionCasing.apply("secretToken", Style.UPPER, "secretToken"))
        assertEquals("OTHER", SuggestionCasing.apply("other", Style.UPPER, "secretToken"))
    }
}
