package com.bugzapperlabs.myfeeds.data.opml

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class OpmlParserTest {
    @Test
    fun parse_nestedOutlines_mapToFoldersAndFeeds() {
        val opml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="1.0">
              <body>
                <outline text="Tech">
                  <outline text="Ars Technica" xmlUrl="https://feeds.arstechnica.com/arstechnica/index/" />
                  <outline text="Engadget" xmlUrl="https://www.engadget.com/rss.xml" />
                </outline>
                <outline text="News">
                  <outline text="BBC News" xmlUrl="https://feeds.bbci.co.uk/news/rss.xml" />
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val document = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals(2, document.folders.size)
        assertEquals("Tech", document.folders[0].name)
        assertEquals(
            listOf("Ars Technica", "Engadget"),
            document.folders[0].feeds.map { it.title },
        )
        assertEquals("News", document.folders[1].name)
        assertEquals(listOf("BBC News"), document.folders[1].feeds.map { it.title })
    }

    @Test
    fun parse_bodyLevelFeed_fallsIntoUncategorized() {
        val opml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="1.0">
              <body>
                <outline text="Standalone Feed" xmlUrl="https://example.com/feed.xml" />
                <outline text="Tech">
                  <outline text="Engadget" xmlUrl="https://www.engadget.com/rss.xml" />
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val document = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals(listOf("Tech", "Uncategorized"), document.folders.map { it.name })
        assertEquals(
            listOf(OpmlFeed("Standalone Feed", "https://example.com/feed.xml")),
            document.folders.last().feeds,
        )
    }

    @Test
    fun parse_folderWithNoFeeds_producesEmptyFeedList() {
        val opml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="1.0">
              <body>
                <outline text="Empty Category" />
              </body>
            </opml>
        """.trimIndent()

        val document = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals(listOf("Empty Category"), document.folders.map { it.name })
        assertEquals(emptyList<OpmlFeed>(), document.folders.first().feeds)
    }

    @Test
    fun parse_bundledDefaultFeedsAsset_parsesWithoutError() {
        val input = javaClass.classLoader!!.getResourceAsStream("default_feeds.opml")
            ?: error("default_feeds.opml not found on test classpath")

        val document = OpmlParser.parse(input)

        assertEquals(3, document.folders.size)
        assertEquals(setOf("Tech", "Mobile", "News"), document.folders.map { it.name }.toSet())
        document.folders.forEach { category -> assertEquals(4, category.feeds.size) }
    }
}
