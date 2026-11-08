package com.noircipher.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlEmailDetectorTest {
    @Test fun emptyInputIsNone() {
        assertEquals(UrlEmailDetector.Detection.NONE, UrlEmailDetector.detect("", ""))
    }

    @Test fun plainWordIsNone() {
        assertFalse(UrlEmailDetector.detect("hello", "say hello").anyActive)
    }

    @Test fun detectsEmailInProgress() {
        val d = UrlEmailDetector.detect("john@example", "write to john@example")
        assertTrue(d.isEmail)
        assertTrue(UrlEmailDetector.suppressSuggestions(d))
        assertTrue(UrlEmailDetector.suppressAutocorrect(d))
    }

    @Test fun detectsUrlSchemeAndWww() {
        assertTrue(UrlEmailDetector.detect("https://exa", "https://exa").isUrl)
        assertTrue(UrlEmailDetector.detect("www.foo", "go to www.foo").isUrl)
    }

    @Test fun detectsUrlWithPath() {
        assertTrue(UrlEmailDetector.detect("example.com/fo", "go to example.com/fo").isUrl)
    }

    @Test fun bareDomainWithoutSchemeOrPathIsNotTreatedAsUrl() {
        // Ambiguous with "end of sentence.Next"; documented behaviour.
        assertFalse(UrlEmailDetector.detect("example.com", "visit example.com").isUrl)
    }

    @Test fun detectsPhoneNumber() {
        assertTrue(UrlEmailDetector.detect("", "call 555-1234").isPhone)
    }

    @Test fun emailDetectionStopsAfterSpace() {
        // Regression: after "john@example.com " the user starts a new word; suggestions
        // must come back instead of staying suppressed.
        assertTrue(UrlEmailDetector.detect("", "mail john@example.com").isEmail)
        assertFalse(UrlEmailDetector.detect("", "mail john@example.com ").anyActive)
    }

    @Test fun urlDetectionStopsAfterSpace() {
        assertFalse(UrlEmailDetector.detect("", "open https://example.com/a ").anyActive)
    }

    @Test fun noneDoesNotSuppress() {
        assertFalse(UrlEmailDetector.suppressSuggestions(UrlEmailDetector.Detection.NONE))
        assertFalse(UrlEmailDetector.suppressAutocorrect(UrlEmailDetector.Detection.NONE))
    }
}
