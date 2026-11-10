package com.noircipher.shade.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipyParserTest {

    private val sized = """
        {"result":true,"data":{"data":[
          {"slug":"one","title":"First","file":{
              "hd":{"gif":{"url":"https://static.klipy.com/one-hd.gif","width":480,"height":270},
                    "webp":{"url":"https://static.klipy.com/one-hd.webp","width":480,"height":270}},
              "md":{"gif":{"url":"https://static.klipy.com/one-md.gif","width":320,"height":180},
                    "webp":{"url":"https://static.klipy.com/one-md.webp","width":320,"height":180}},
              "sm":{"gif":{"url":"https://static.klipy.com/one-sm.gif","width":160,"height":90},
                    "webp":{"url":"https://static.klipy.com/one-sm.webp","width":160,"height":90}}}},
          {"slug":"ad1","kind":"ad","title":"Sponsored","file":{"md":{"gif":{"url":"https://x.example/ad.gif"}}}},
          {"slug":"two","title":"Second","file":{"md":{"gif":{"url":"https://static1.klipy.com/two.gif","width":200,"height":200}}}},
          {"slug":"noformat","title":"Broken","file":{"md":{"mp4":{"url":"https://static.klipy.com/x.mp4"}}}},
          {"slug":"insecure","title":"Http","file":{"md":{"gif":{"url":"http://static.klipy.com/x.gif"}}}}
        ],"current_page":2,"per_page":24,"has_next":true}}
    """.trimIndent()

    @Test fun parsesPagingFields() {
        val page = KlipyParser.parsePage(sized, animatedPreviews = true)
        assertEquals(2, page.page)
        assertTrue(page.hasNext)
    }

    @Test fun dropsAdsItemsWithoutUsableMediaAndPlainHttp() {
        val page = KlipyParser.parsePage(sized, animatedPreviews = true)
        assertEquals(listOf("one", "two"), page.items.map { it.slug })
    }

    @Test fun animatedPreviewUsesSmallestSizeAndWebp() {
        val one = KlipyParser.parsePage(sized, animatedPreviews = true).items.first()
        assertEquals("https://static.klipy.com/one-sm.webp", one.preview.url)
        assertEquals("image/webp", one.preview.mime)
    }

    @Test fun staticPreviewFallsBackToGif() {
        val one = KlipyParser.parsePage(sized, animatedPreviews = false).items.first()
        assertEquals("https://static.klipy.com/one-sm.gif", one.preview.url)
        assertEquals("image/gif", one.preview.mime)
    }

    @Test fun sendRenditionIsAMediumGif() {
        val one = KlipyParser.parsePage(sized, animatedPreviews = true).items.first()
        assertEquals("https://static.klipy.com/one-md.gif", one.send.url)
        assertEquals("image/gif", one.send.mime)
        assertEquals(320, one.send.width)
    }

    @Test fun itemWithOnlyOneRenditionUsesItForBoth() {
        val two = KlipyParser.parsePage(sized, animatedPreviews = true).items[1]
        assertEquals("https://static1.klipy.com/two.gif", two.preview.url)
        assertEquals("https://static1.klipy.com/two.gif", two.send.url)
    }

    @Test fun acceptsFlatFileLayoutAndFilesKey() {
        val json = """{"data":{"data":[{"slug":"flat","title":"Flat",
            "files":{"gif":{"url":"https://static.klipy.com/flat.gif","width":10,"height":10}}}],
            "current_page":1,"has_next":false}}"""
        val page = KlipyParser.parsePage(json, animatedPreviews = false)
        assertEquals(1, page.items.size)
        assertFalse(page.hasNext)
        assertEquals("https://static.klipy.com/flat.gif", page.items[0].send.url)
    }

    @Test fun emptyOrUnexpectedBodiesGiveAnEmptyPage() {
        assertTrue(KlipyParser.parsePage("""{"result":true,"data":{"data":[],"has_next":false}}""", true).items.isEmpty())
        assertTrue(KlipyParser.parsePage("{}", true).items.isEmpty())
    }

    @Test fun pickReturnsNullWhenNothingMatches() {
        val file = org.json.JSONObject("""{"md":{"mp4":{"url":"https://a/b.mp4"}}}""")
        assertNull(KlipyParser.pick(file, listOf("md"), listOf("gif"), sizesFirst = true))
    }
}
