package com.shade.keyboard.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Parsing layer of the learned-word importer: every exported file shape must
 * yield the same words, and settings debris must stay out.
 */
class DictionaryImportTest {

    private fun harvest(bytes: ByteArray) = DictionaryImport.parseBytes(bytes)

    private fun harvest(text: String) = harvest(text.toByteArray(Charsets.UTF_8))

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ json

    @Test
    fun arrayOfStringsIsAWordList() {
        val h = harvest("""["alpha", "beta", "gamma"]""")
        assertEquals(listOf("alpha", "beta", "gamma"), h.words.keys.toList())
    }

    @Test
    fun wordEntriesWithSiblingFrequency() {
        val h = harvest("""{"words":[{"word":"Alpha","frequency":7},{"word":"betta"}],"meta":{"x":1}}""")
        assertEquals(setOf("alpha", "betta"), h.words.keys)
        assertEquals(7, h.words["alpha"])
        assertEquals(1, h.words["betta"])
        // The "meta" object must not leak its values into the dictionary.
        assertTrue("x" !in h.words)
    }

    @Test
    fun bareWordToFrequencyMap() {
        val h = harvest("""{"cat": 5, "dog": 2}""")
        assertEquals(5, h.words["cat"])
        assertEquals(2, h.words["dog"])
    }

    @Test
    fun settingsDebrisStaysOut() {
        val h = harvest("""{"theme":"dark","languages":["en","fr"],"sound":true,"words":["wanted"]}""")
        assertTrue("dark" !in h.words)
        assertTrue("en" !in h.words)
        assertTrue("fr" !in h.words)
        assertEquals(setOf("wanted"), h.words.keys)
    }

    @Test
    fun nestedEntryListsAreFound() {
        val h = harvest("""{"a":{"entries":["first","second"]},"b":{"user_dict":[{"phrase":"third term"}]}}""")
        assertTrue("first" in h.words && "second" in h.words)
        // Multi-word phrases are rejected by the word rules (spaces not allowed).
        assertTrue("third term" !in h.words)
    }

    // ------------------------------------------------------------------ text

    @Test
    fun plainWordListWithOptionalCounts() {
        val h = harvest("hello\nworld 4\nfoo\t9\n# comment\nhttps://example.com/x\n\nzebra")
        assertEquals(setOf("hello", "world", "foo", "zebra"), h.words.keys)
        assertEquals(4, h.words["world"])
        assertEquals(9, h.words["foo"])
    }

    @Test
    fun invalidLinesCountAsInvalid() {
        val h = harvest("good\nx\n12345\n")
        assertEquals(setOf("good"), h.words.keys)
        assertEquals(2, h.invalid)
    }

    @Test
    fun trailingApostropheIsDropped() {
        val h = harvest("dogs'\n")
        assertEquals(setOf("dogs"), h.words.keys)
    }

    // ------------------------------------------------------------------ zip

    @Test
    fun zipEntriesAreParsedAsTextOrJson() {
        val bytes = zipOf(
            mapOf(
                "backup/settings.json" to """{"theme":"dark","version":2}""",
                "backup/userdictionary.json" to """{"words":[{"word":"fromzip","freq":3}]}""",
                "backup/words.txt" to "plainone\nplaintwo 6\n",
                "backup/model.bin" to byteArrayOf(0, 1, 2, 3).toString(Charsets.ISO_8859_1)
            )
        )
        val h = harvest(bytes)
        assertEquals(setOf("fromzip", "plainone", "plaintwo"), h.words.keys)
        assertEquals(3, h.words["fromzip"])
        assertEquals(6, h.words["plaintwo"])
        assertTrue("dark" !in h.words)
        assertTrue(h.sources.any { it.endsWith("userdictionary.json") })
    }

    @Test
    fun duplicateWordsKeepTheStrongestFrequency() {
        val h = harvest("""["echo", "echo"]""")
        assertEquals(listOf("echo"), h.words.keys.toList())
    }

    @Test
    fun unknownBinaryIsIgnoredGracefully() {
        val h = harvest(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        // Neither words nor source labels may be produced for unrecognizable data.
        assertTrue(h.words.isEmpty() && h.sources.isEmpty())
    }
}
