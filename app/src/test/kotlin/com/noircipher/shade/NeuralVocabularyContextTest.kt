package com.noircipher.shade

import com.noircipher.shade.ai.NeuralVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** The next-word fit that glide typing takes from the neural model's logits. */
class NeuralVocabularyContextTest {

    private val vocab = NeuralVocabulary.fromText(
        "0\t5\t\n1\t5\t[BOS]\n2\t5\t[EOS]\n3\t5\t[UNK]\n4\t4\tthe\n5\t3\tcat\n6\t2\tdog\n7\t1\tzebra"
    )!!

    // probabilities after softmax: the 4/7, cat 2/7, dog 1/7, zebra ~0; the four special entries ~0
    private val logits = floatArrayOf(-50f, -50f, -50f, -50f, ln(4f), ln(2f), 0f, -20f)

    @Test fun fitFollowsTheModelsProbabilities() {
        val b = vocab.contextBonuses(logits)
        assertEquals(0.9534f, b.getValue("the"), 1e-3f)
        assertEquals(0.8956f, b.getValue("cat"), 1e-3f)
        assertEquals(0.8378f, b.getValue("dog"), 1e-3f)
        assertTrue(b.getValue("the") > b.getValue("cat") && b.getValue("cat") > b.getValue("dog"))
    }

    @Test fun veryUnlikelyWordsAndSpecialTokensGetNothing() {
        val b = vocab.contextBonuses(logits)
        assertFalse(b.containsKey("zebra"))      // probability ~3e-10, below e^-12
        for (special in listOf("[BOS]", "[EOS]", "[UNK]", "")) assertFalse(special, b.containsKey(special))
        assertEquals(3, b.size)
    }

    @Test fun allValuesAreInTheUnitRange() {
        for (v in vocab.contextBonuses(logits).values) assertTrue(v > 0f && v <= 1f)
    }

    @Test fun smallerScaleCutsTheTailEarlier() {
        val b = vocab.contextBonuses(logits, scale = 1f)
        assertNotNull(b["the"])
        assertFalse(b.containsKey("dog"))        // 1 + ln(1/7) < 0
    }

    @Test fun emptyLogitsOrNonPositiveScaleGiveNothing() {
        assertTrue(vocab.contextBonuses(FloatArray(0)).isEmpty())
        assertTrue(vocab.contextBonuses(logits, scale = 0f).isEmpty())
    }

    @Test fun modelsThatOmitThePlaceholderEntryAreAlignedCorrectly() {
        // Some exports drop the empty id-0 token: the output then starts at id 1.
        val shifted = logits.copyOfRange(1, logits.size)
        val b = vocab.contextBonuses(shifted)
        assertEquals(0.9534f, b.getValue("the"), 1e-3f)
        assertEquals(0.8378f, b.getValue("dog"), 1e-3f)
    }
}
