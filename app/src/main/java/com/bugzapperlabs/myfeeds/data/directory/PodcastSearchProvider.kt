package com.bugzapperlabs.myfeeds.data.directory

/** One search result, common to every [PodcastSearchProvider] implementation (issue #93) --
 *  [subtitle] is a short secondary line shown under the title (the offline directory's category,
 *  a live search result's author/owner name, etc.), left generic so callers don't need to know
 *  which provider produced a given result. */
data class PodcastSearchResult(
    val title: String,
    val feedUrl: String,
    val subtitle: String,
    val description: String?,
)

/**
 * Strategy interface for podcast/feed discovery search (issue #93), so the active search
 * implementation can be swapped without changing callers -- see [PodcastSearchService] (picks
 * between implementations at runtime), [com.bugzapperlabs.myfeeds.data.directory.FeedDirectory]
 * (offline snapshot search) and [PodcastIndexSearchProvider] (live podcastindex.org search).
 */
interface PodcastSearchProvider {
    suspend fun search(query: String, limit: Int = 50): List<PodcastSearchResult>
}
