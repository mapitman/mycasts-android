package com.bugzapperlabs.mycasts.data.opml

import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Mirror image of [OpmlParser]/[OpmlImporter]. Categories are gone (issue #118), and the
 * Podcasts/Feeds split is gone too (issue #10) now that every feed is a podcast -- every
 * subscribed feed is exported into a single "Podcasts" outline, omitted entirely if empty.
 */
class OpmlExporter @Inject constructor(
    private val feedDao: FeedDao,
) {
    suspend fun export(): String {
        val feeds = feedDao.observeAll().first()

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<opml version=\"1.0\">\n")
            append("  <head>\n")
            append("    <title>MyCasts Exported Feeds</title>\n")
            append("  </head>\n")
            append("  <body>\n")
            appendFolder("Podcasts", feeds)
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
