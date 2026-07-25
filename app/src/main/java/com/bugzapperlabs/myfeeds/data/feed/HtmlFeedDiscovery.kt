package com.bugzapperlabs.myfeeds.data.feed

import org.jsoup.Jsoup

/**
 * Ported from MyFeeds/HtmlUtility.cs::FindRssLinks: scan `<link rel="alternate">` tags for
 * `application/rss+xml` or `application/atom+xml`, resolve relative hrefs against the page URL,
 * and keep only absolute http(s) results (matches FeedManager.UpdateFeed's
 * `u.IsAbsoluteUri && u.AbsoluteUri.StartsWith("http")` filter).
 */
object HtmlFeedDiscovery {
    private val FEED_TYPES = setOf("application/rss+xml", "application/atom+xml")

    fun findFeedUrl(html: String, baseUrl: String): String? {
        val document = try {
            Jsoup.parse(html, baseUrl)
        } catch (_: Exception) {
            return null
        }

        return document.select("link[rel=alternate]")
            .filter { it.attr("type") in FEED_TYPES }
            .map { it.attr("abs:href") }
            .firstOrNull { it.startsWith("http", ignoreCase = true) }
    }
}
