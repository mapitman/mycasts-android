package com.bugzapperlabs.myfeeds.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("feed-fixtures/$name")
            ?: error("fixture $name not found")

    @Test
    fun parseRss_mapsChannelAndItemFields() {
        val feed = FeedParser.parse(fixture("rss.xml"))!!

        assertEquals("Ars Technica", feed.title)
        assertEquals("https://arstechnica.com", feed.siteUrl)
        assertEquals("https://arstechnica.com/logo.png", feed.imageUrl)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("First Post & Things", first.title)
        assertEquals("urn:uuid:first-post", first.itemGuid)
        assertEquals("<p>Rich <b>content</b> body.</p>", first.description)
        assertEquals(Instant.parse("2013-06-03T11:05:30Z"), first.publishDate)
    }

    @Test
    fun parseRss_descriptionFallsBackWhenNoContentEncoded() {
        val feed = FeedParser.parse(fixture("rss.xml"))!!

        val second = feed.items[1]
        assertEquals("Only a plain description, no content:encoded.", second.description)
    }

    @Test
    fun parseRss_extractsPodcastChaptersUrl() {
        val feed = FeedParser.parse(fixture("rss.xml"))!!

        assertEquals("https://arstechnica.com/first-post/chapters.json", feed.items[0].chaptersUrl)
        assertNull(feed.items[1].chaptersUrl)
    }

    @Test
    fun parseRss_itemGuidFallsBackToLinkWhenGuidMissing() {
        val feed = FeedParser.parse(fixture("rss.xml"))!!

        val second = feed.items[1]
        assertEquals("https://arstechnica.com/second-post", second.itemGuid)
    }

    @Test
    fun parseRss_urlFallsBackToPermalinkGuidWhenLinkMissing() {
        // issue #221: a common valid RSS pattern omits <link> entirely and marks <guid
        // isPermaLink="true"> (the spec's default when the attribute is absent) as the article's
        // web URL instead.
        val xml = """
            <rss version="2.0"><channel>
              <title>Test</title>
              <item>
                <title>No Link Item</title>
                <guid isPermaLink="true">https://example.com/no-link-item</guid>
                <description>Body</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml)!!.items.single()

        assertEquals("https://example.com/no-link-item", item.url)
        assertEquals("https://example.com/no-link-item", item.itemGuid)
    }

    @Test
    fun parseRss_doesNotFallBackToGuidWhenIsPermaLinkIsFalse() {
        val xml = """
            <rss version="2.0"><channel>
              <title>Test</title>
              <item>
                <title>Opaque Guid Item</title>
                <guid isPermaLink="false">not-a-url-123</guid>
                <description>Body</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml)!!.items.single()

        assertEquals("", item.url)
    }

    @Test
    fun parseRss_podcastEnclosureFieldsExtracted() {
        val feed = FeedParser.parse(fixture("rss-podcast.xml"))!!

        val enclosure = feed.items.single().enclosure
        assertNotNull(enclosure)
        assertEquals("https://cdn.twit.tv/windows-weekly-900.mp3", enclosure!!.url)
        assertEquals("audio/mpeg", enclosure.type)
        assertEquals(123456789L, enclosure.length)
    }

    @Test
    fun parseRss_itunesImagePreferredOverPlainRssImage() {
        val feed = FeedParser.parse(fixture("rss-podcast.xml"))!!

        assertEquals("https://cdn.twit.tv/windows-weekly-cover.jpg", feed.imageUrl)
    }

    @Test
    fun parseRss_itunesImageHttpUrlUpgradedToHttps() {
        // issue #149: a plain http:// image URL is silently blocked by cleartext-traffic
        // restrictions (targetSdk 28+), so podcast cover art fails to render -- upgrade the
        // scheme rather than requiring the feed to serve https itself.
        val feed = FeedParser.parse(fixture("rss-podcast-http-image.xml"))!!

        assertEquals("https://latenightlinux.com/wp-content/uploads/latenightlinux.jpg", feed.imageUrl)
    }

    @Test
    fun parseRss_itunesDurationParsedToMillis() {
        val feed = FeedParser.parse(fixture("rss-podcast.xml"))!!

        // <itunes:duration>2:19:36</itunes:duration> -- 2h19m36s
        assertEquals(8_376_000L, feed.items.single().durationMs)
    }

    @Test
    fun parseRss_itunesDurationAcceptsMinutesSecondsAndPlainSeconds() {
        assertEquals(1_176_000L, parseSingleItemDuration("19:36"))
        assertEquals(8_396_000L, parseSingleItemDuration("8396"))
    }

    @Test
    fun parseRss_itunesDurationMissingIsNull() {
        assertNull(parseSingleItemDuration(null))
    }

    private fun parseSingleItemDuration(duration: String?): Long? {
        val durationTag = duration?.let { "<itunes:duration>$it</itunes:duration>" }.orEmpty()
        val xml = """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Test</title>
                <item>
                  <title>Item</title>
                  <link>https://example.com/item</link>
                  <guid>item-1</guid>
                  <enclosure url="https://example.com/item.mp3" type="audio/mpeg" length="1" />
                  $durationTag
                </item>
              </channel>
            </rss>
        """.trimIndent()
        return FeedParser.parse(xml)!!.items.single().durationMs
    }

    @Test
    fun parseAtom_prefersContentOverSummaryInDocumentOrder() {
        val feed = FeedParser.parse(fixture("atom.xml"))!!

        assertEquals("A Blog", feed.title)
        assertEquals("https://example.com", feed.siteUrl)
        assertEquals("https://example.com/logo.png", feed.imageUrl)

        val first = feed.items[0]
        assertEquals("<p>Full content body.</p>", first.description)
        assertEquals("urn:uuid:entry-1", first.itemGuid)
        assertEquals(Instant.parse("2013-06-03T11:05:30Z"), first.publishDate)
    }

    @Test
    fun parseAtom_prefersContentOverSummaryRegardlessOfDocumentOrder() {
        // issue #223: firstLocalNameOrNull("content", "summary") used to be a single document-order
        // scan across both names combined, so a <summary> appearing before <content> would win.
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Test</title>
              <entry>
                <title>Summary First Entry</title>
                <id>urn:uuid:summary-first</id>
                <summary>Should not be used -- content takes precedence even though it's later.</summary>
                <content type="html">Real content body.</content>
              </entry>
            </feed>
        """.trimIndent()

        val item = FeedParser.parse(xml)!!.items.single()

        assertEquals("Real content body.", item.description)
    }

    @Test
    fun parseAtom_ignoresUnrelatedNamespacedElementSharingContentLocalName() {
        // issue #223: an empty Media RSS <media:content url="..."/> collapses to local name
        // "content" once namespace prefixes are stripped -- it must not be mistaken for the real
        // Atom <content> and short-circuit to a blank description.
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <title>Test</title>
              <entry>
                <title>Media Content Entry</title>
                <id>urn:uuid:media-content</id>
                <media:content url="https://example.com/thumb.jpg" />
                <summary>The real summary text.</summary>
              </entry>
            </feed>
        """.trimIndent()

        val item = FeedParser.parse(xml)!!.items.single()

        assertEquals("The real summary text.", item.description)
    }

    @Test
    fun parseAtom_fallsBackToSummaryAndLinkWhenNoContentOrId() {
        val feed = FeedParser.parse(fixture("atom.xml"))!!

        val second = feed.items[1]
        assertEquals("Just a summary, no content element.", second.description)
        assertEquals("https://example.com/entry-2", second.itemGuid)
        assertEquals("https://example.com/entry-2", second.url)
    }

    @Test
    fun parseAtom_linkWithTypeButNoRelIsTreatedAsAlternate() {
        // issue #221: a <link> with no rel attribute defaults to rel="alternate" per the Atom
        // spec -- previously this was only matched when its type was also blank, so a link with an
        // explicit type but no rel (e.g. type="text/html") was skipped, leaving the item linkless.
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Test</title>
              <entry>
                <title>No Rel Link Entry</title>
                <id>urn:uuid:no-rel-entry</id>
                <link type="text/html" href="https://example.com/no-rel-entry" />
              </entry>
            </feed>
        """.trimIndent()

        val item = FeedParser.parse(xml)!!.items.single()

        assertEquals("https://example.com/no-rel-entry", item.url)
    }

    @Test
    fun parseRdf_prefersContentEncodedAndFiltersNonAudioEnclosures() {
        val feed = FeedParser.parse(fixture("rdf.xml"))!!

        assertEquals("An RDF Feed", feed.title)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("<p>Rich RDF content.</p>", first.description)
        assertEquals("https://example.com/rdf-item-1", first.itemGuid)
        assertNotNull(first.enclosure)
        assertEquals("audio/mpeg", first.enclosure!!.type)

        val second = feed.items[1]
        assertEquals("No content:encoded here.", second.description)
        assertNull(second.enclosure)
    }

    @Test
    fun parseRdf_itemGuidIsAlwaysTheLink() {
        val feed = FeedParser.parse(fixture("rdf.xml"))!!

        feed.items.forEach { item -> assertEquals(item.url, item.itemGuid) }
    }

    @Test
    fun parse_stripsControlCharsAndRetriesOnParseFailure() {
        val feed = FeedParser.parse(fixture("rss-control-chars.xml"))

        assertNotNull(feed)
        assertEquals("Feed With Control Chars", feed!!.title)
        assertTrue(feed.items.single().title.contains("Item"))
    }

    @Test
    fun parse_unrecognizedRootElement_returnsNull() {
        val feed = FeedParser.parse("<notafeed><thing/></notafeed>")

        assertNull(feed)
    }

    @Test
    fun parse_malformedXmlEvenAfterCleanup_returnsNull() {
        val feed = FeedParser.parse("<rss version=\"2.0\"><channel><title>Unclosed")

        assertNull(feed)
    }
}
