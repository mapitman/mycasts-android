package com.bugzapperlabs.myfeeds.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlFeedDiscoveryTest {
    @Test
    fun findFeedUrl_resolvesRelativeHrefAgainstBaseUrl() {
        val html = """
            <html><head>
              <link rel="alternate" type="application/rss+xml" href="/feed.xml" />
            </head></html>
        """.trimIndent()

        val result = HtmlFeedDiscovery.findFeedUrl(html, "https://example.com/blog")

        assertEquals("https://example.com/feed.xml", result)
    }

    @Test
    fun findFeedUrl_prefersAbsoluteHrefAsIs() {
        val html = """
            <html><head>
              <link rel="alternate" type="application/atom+xml" href="https://cdn.example.com/atom.xml" />
            </head></html>
        """.trimIndent()

        val result = HtmlFeedDiscovery.findFeedUrl(html, "https://example.com/blog")

        assertEquals("https://cdn.example.com/atom.xml", result)
    }

    @Test
    fun findFeedUrl_ignoresNonFeedAlternateLinks() {
        val html = """
            <html><head>
              <link rel="alternate" type="application/json" href="/feed.json" />
              <link rel="stylesheet" href="/style.css" />
            </head></html>
        """.trimIndent()

        assertNull(HtmlFeedDiscovery.findFeedUrl(html, "https://example.com"))
    }

    @Test
    fun findFeedUrl_noLinkTags_returnsNull() {
        assertNull(HtmlFeedDiscovery.findFeedUrl("<html><body>Hello</body></html>", "https://example.com"))
    }
}
