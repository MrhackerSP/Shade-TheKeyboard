package com.noircipher.shade

import com.noircipher.shade.engine.ConfusablePairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfusablePairsTest {
    @Test fun previousWordRules() {
        assertEquals("than", ConfusablePairs.correct("then", "better"))
        assertEquals("Than", ConfusablePairs.correct("Then", "more"))
        assertEquals("then", ConfusablePairs.correct("than", "and"))
        assertNull(ConfusablePairs.correct("then", "and"))
        assertNull(ConfusablePairs.correct("then", null))
    }

    @Test fun nextWordRulesFixThePreviousWord() {
        assertEquals("you're", ConfusablePairs.correctPrevious("your", "welcome", null))
        assertEquals("too", ConfusablePairs.correctPrevious("to", "late", "was"))
        assertEquals("there", ConfusablePairs.correctPrevious("their", "is", null))
        assertEquals("their", ConfusablePairs.correctPrevious("there", "own", "on"))
    }

    @Test fun legitimatePhrasesAreLeftAlone() {
        assertNull(ConfusablePairs.correctPrevious("to", "go", "want"))        // "want to go"
        assertNull(ConfusablePairs.correctPrevious("to", "much", "up"))        // "not up to much"
        assertNull(ConfusablePairs.correctPrevious("there", "family", "is"))   // "is there family"
        assertNull(ConfusablePairs.correctPrevious("they're", "friends", null))
        assertNull(ConfusablePairs.correctPrevious("our", "going", "for"))     // needs we/you/they before it
        assertNull(ConfusablePairs.correctPrevious("were", "going", "they"))
    }

    @Test fun caseIsPreserved() {
        assertEquals("You're", ConfusablePairs.matchCase("Your", "you're"))
        assertEquals("YOU'RE", ConfusablePairs.matchCase("YOUR", "you're"))
    }
}
