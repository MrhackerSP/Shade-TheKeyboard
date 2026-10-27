package com.noircipher.shade

import com.noircipher.shade.engine.PhraseBooster
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseBoosterTest {
    @Test
    fun howSuggestsAre() {
        assertEquals("are", PhraseBooster.predict("how", 1).firstOrNull())
    }

    @Test
    fun areSuggestsYou() {
        assertEquals("you", PhraseBooster.predict("are", 1).firstOrNull())
    }

    @Test
    fun howWithPrefixA() {
        assertEquals("are", PhraseBooster.predict("how", "a", 1).firstOrNull())
    }
}
