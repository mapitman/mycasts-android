package com.bugzapperlabs.myfeeds.data.directory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.data.opml.OpmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, offline keyword search over a bundled directory of feeds (issue #62): since no free,
 * ToS-compliant API existed at the time for live feed-topic search (see the research on issue
 * #31), this searches a snapshot combined from https://github.com/plenaryapp/awesome-rss-feeds
 * (CC0-licensed) rather than calling out to a server. See assets/feed_directory.opml. Now one of
 * two [PodcastSearchProvider] implementations (issue #93) -- [PodcastSearchService] prefers the
 * live podcastindex.org search when configured and falls back to this one otherwise/on failure.
 */
@Singleton
class FeedDirectory @Inject constructor(@ApplicationContext private val context: Context) : PodcastSearchProvider {
    private val mutex = Mutex()
    private var entries: List<PodcastSearchResult>? = null

    override suspend fun search(query: String, limit: Int): List<PodcastSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val all = loadEntries()
        val needle = trimmed.lowercase()

        return all.asSequence()
            .mapNotNull { entry -> entry.matchScore(needle)?.let { entry to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** Lower is more relevant: title match ranks above description/category(subtitle)-only matches. */
    private fun PodcastSearchResult.matchScore(needle: String): Int? = when {
        title.contains(needle, ignoreCase = true) -> 0
        description?.contains(needle, ignoreCase = true) == true -> 1
        subtitle.contains(needle, ignoreCase = true) -> 2
        else -> null
    }

    private suspend fun loadEntries(): List<PodcastSearchResult> {
        entries?.let { return it }
        return mutex.withLock {
            entries?.let { return it }
            val loaded = withContext(Dispatchers.IO) {
                context.assets.open(DIRECTORY_ASSET).use { OpmlParser.parse(it) }
                    .folders.flatMap { folder ->
                        folder.feeds.map { feed ->
                            PodcastSearchResult(
                                title = feed.title,
                                feedUrl = feed.xmlUrl,
                                subtitle = folder.name,
                                description = feed.description,
                            )
                        }
                    }
            }
            entries = loaded
            loaded
        }
    }

    private companion object {
        const val DIRECTORY_ASSET = "feed_directory.opml"
    }
}
