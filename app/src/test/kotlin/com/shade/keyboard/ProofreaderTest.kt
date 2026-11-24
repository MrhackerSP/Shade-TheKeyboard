package com.shade.keyboard

import com.shade.keyboard.engine.MultiWordCorrector
import org.junit.Assert.assertEquals
import org.junit.Test

class ProofreaderTest {

    private fun proofreader(
        known: Set<String> = emptySet(),
        dict: Map<String, String> = emptyMap(),
        neural: (String) -> List<String> = { emptyList() },
        neuralBudgetMs: Long = 1200L
    ) = Proofreader(
        isKnown = { it in known },
        resolve = { w ->
            MultiWordCorrector.correct(w, isDictionaryWord = w in known)
                ?: if (w in known) null else dict[w]
        },
        neuralResolve = neural,
        neuralBudgetMs = neuralBudgetMs
    )

    @Test
    fun bareICapitalized() {
        val r = proofreader().proofread("i think so")
        assertEquals("I think so", r.text)
        assertEquals(1, r.fixCount)
    }

    @Test
    fun contractionFixedWithCasing() {
        assertEquals("Don't worry", proofreader().proofread("Dont worry").text)
    }

    @Test
    fun dictionaryWordIsKept() {
        val p = proofreader(known = setOf("hello", "world"))
        val r = p.proofread("Hello world")
        assertEquals("Hello world", r.text)
        assertEquals(0, r.fixCount)
    }

    @Test
    fun acronymUntouched() {
        val r = proofreader().proofread("NASA and FYI")
        assertEquals("NASA and FYI", r.text)
        assertEquals(0, r.fixCount)
    }

    @Test
    fun doubleSpaceCollapsed() {
        val r = proofreader().proofread("hello  world")
        assertEquals("Hello world", r.text)
        assertEquals(2, r.fixCount)
    }

    @Test
    fun spaceBeforePunctuationDropped() {
        assertEquals("Hello, world!", proofreader().proofread("hello , world !").text)
    }

    @Test
    fun missingSpaceAfterCommaInserted() {
        assertEquals("One, two", proofreader().proofread("one,two").text)
    }

    @Test
    fun sentencePunctuationGetsSpaceBeforeCapital() {
        assertEquals("End. Next day", proofreader().proofread("end.Next day").text)
    }

    @Test
    fun decimalsAndDomainsUntouched() {
        val p = proofreader(known = setOf("value", "index", "html"))
        assertEquals("Value 3.5 at index.html", p.proofread("value 3.5 at index.html").text)
    }

    @Test
    fun acronymAfterPeriodGetsNoSpace() {
        assertEquals("The U.S.A rocks", proofreader(known = setOf("the", "rocks")).proofread("the U.S.A rocks").text)
    }

    @Test
    fun sentenceStartCapitalized() {
        val r = proofreader(known = setOf("hello", "there", "world"))
        assertEquals("Hello there. World", r.proofread("hello there. world").text)
    }

    @Test
    fun urlTokensUntouched() {
        val p = proofreader(known = setOf("visit", "today"))
        assertEquals("Visit https://example.com today", p.proofread("visit https://example.com today").text)
    }

    @Test
    fun nonAsciiPassesThrough() {
        val r = proofreader().proofread("मेरा नाम")
        assertEquals("मेरा नाम", r.text)
        assertEquals(0, r.fixCount)
    }

    @Test
    fun neuralFallbackAppliedWithinBudget() {
        val r = proofreader(neural = { w -> if (w == "wrks") listOf("works") else emptyList() }).proofread("wrks fine")
        assertEquals("Works fine", r.text)
    }

    @Test
    fun neuralDisabledAfterBudgetExhausted() {
        val r = proofreader(neural = { w -> if (w == "wrks") listOf("works") else emptyList() }, neuralBudgetMs = 0L).proofread("wrks fine")
        assertEquals("Wrks fine", r.text)
    }

    @Test
    fun emptyInput() {
        val r = proofreader().proofread("")
        assertEquals("", r.text)
        assertEquals(0, r.fixCount)
    }

    @Test
    fun inputCappedAtMax() {
        val r = proofreader().proofread("a".repeat(6000))
        assertEquals(Proofreader.MAX_INPUT, r.text.length)
    }

    @Test
    fun combinedFixCount() {
        val r = proofreader().proofread("i cant go  ,tomorrow")
        assertEquals("I can't go, tomorrow", r.text)
        assertEquals(4, r.fixCount)
    }
}
