package com.bugzapperlabs.myfeeds.data.opml

import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.local.FeedItemDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Mirror image of [OpmlParser]/[OpmlImporter]. Categories are gone (issue #118), so feeds are
 * grouped into the same two fixed folders the feed list uses -- "Podcasts" and "Feeds" -- rather
 * than one outline per category. A folder is omitted entirely if it would be empty.
 */
class OpmlExporter @Inject constructor(
    private val feedDao: FeedDao,
    private val feedItemDao: FeedItemDao,
) {
    suspend fun export(): String {
        val feeds = feedDao.observeAll().first()
        val podcastFeedIds = feedItemDao.observePodcastFeedIds().first().toSet()
        val podcastFeeds = feeds.filter { it.id in podcastFeedIds }
        val otherFeeds = feeds.filterNot { it.id in podcastFeedIds }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<opml version=\"1.0\">\n")
            append("  <head>\n")
            append("    <title>MyFeeds Exported Feeds</title>\n")
            append("  </head>\n")
            append("  <body>\n")
            appendFolder("Podcasts", podcastFeeds)
            appendFolder("Feeds", otherFeeds)
            append("  </body>\n")
            append("</opml>\n")
        }
    }

    private fun StringBuilder.appendFolder(name: String, feeds: List<Feed>) {
        if (feeds.isEmpty()) return
        append("    <outline text=\"${name.xmlEscape()}\">\n")
        feeds.forEach { feed ->
            val title = (feed.userTitle ?: feed.title).orEmpty()
            append(
                "      <outline text=\"${title.xmlEscape()}\" xmlUrl=\"${feed.feedUrl.orEmpty().xmlEscape()}\" />\n",
            )
        }
        append("    </outline>\n")
    }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
