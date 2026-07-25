package com.bugzapperlabs.myfeeds.data.feed

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: FeedFetcher

    private val rssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>A Feed</title>
            <link>https://example.com</link>
            <description>desc</description>
            <item>
              <title>An Item</title>
              <link>https://example.com/item-1</link>
              <guid>item-1</guid>
              <description>body</description>
              <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = FeedFetcher(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchFeed_directXmlResponse_parsesSuccessfully() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))

        val result = fetcher.fetchFeed(server.url("/feed.xml").toString())

        assertTrue(result is FeedFetchResult.Success)
        assertEquals("A Feed", (result as FeedFetchResult.Success).feed.title)
    }

    @Test
    fun fetchFeed_sendsUserAgentHeader() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))

        fetcher.fetchFeed(server.url("/feed.xml").toString())

        val recorded = server.takeRequest()
        assertEquals(FeedFetcher.USER_AGENT, recorded.getHeader("User-Agent"))
    }

    @Test
    fun fetchFeed_htmlWithDiscoverableLink_refetchesDiscoveredUrl() = runTest {
        val html = """
            <html><head>
              <link rel="alternate" type="application/rss+xml" href="/feed.xml" />
            </head></html>
        """.trimIndent()
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(html),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))

        val result = fetcher.fetchFeed(server.url("/").toString())

        assertTrue(result is FeedFetchResult.Success)
        assertEquals("A Feed", (result as FeedFetchResult.Success).feed.title)
        assertTrue(result.resolvedUrl.endsWith("/feed.xml"))
    }

    @Test
    fun fetchFeed_htmlWithNoDiscoverableLink_fails() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("<html><body>No feed here</body></html>"),
        )

        val result = fetcher.fetchFeed(server.url("/").toString())

        assertTrue(result is FeedFetchResult.Failure)
    }

    @Test
    fun fetchFeed_nonSuccessStatus_fails() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = fetcher.fetchFeed(server.url("/missing.xml").toString())

        assertTrue(result is FeedFetchResult.Failure)
        assertTrue((result as FeedFetchResult.Failure).message.contains("404"))
    }

    @Test
    fun fetchFeed_unparseableXml_fails() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<rss><channel><title>Broken"))

        val result = fetcher.fetchFeed(server.url("/feed.xml").toString())

        assertTrue(result is FeedFetchResult.Failure)
    }
}
