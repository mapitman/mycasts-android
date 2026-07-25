package com.bugzapperlabs.myfeeds.data.opml

import com.bugzapperlabs.myfeeds.data.feed.FeedFetchResult
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class OpmlImportResult(
    val importedCount: Int,
    val alreadySubscribedCount: Int,
    val invalidCount: Int,
)

/**
 * Imports a parsed [OpmlDocument]'s flat feed list:
 * - skips feeds already subscribed by [Feed.feedUrl] (issue #228) -- re-importing an OPML file
 *   that overlaps with existing subscriptions used to insert an unconditional duplicate for every
 *   entry, and duplicate entries within the same document are likewise only subscribed once;
 * - validates each remaining feed by actually fetching it before subscribing, so a dead/broken
 *   URL isn't silently added as a permanently-blank feed (issue #231);
 * - populates the newly subscribed feed's title/items immediately from that same fetch, rather
 *   than leaving it blank until the next scheduled refresh (issue #230).
 *
 * Fetches run with the same bounded concurrency as a normal feed refresh
 * ([FeedUpdateEngine.updateFeeds]), so a large OPML file doesn't hammer the network with
 * unbounded parallel requests.
 */
class OpmlImporter @Inject constructor(
    private val feedDao: FeedDao,
    private val feedFetcher: FeedFetcher,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun import(document: OpmlDocument): OpmlImportResult = coroutineScope {
        val seenUrls = mutableSetOf<String>()
        var alreadySubscribedCount = 0
        val candidates = document.feeds.filter { feed ->
            when {
                !seenUrls.add(feed.xmlUrl) -> false
                feedDao.findByFeedUrl(feed.xmlUrl) != null -> {
                    alreadySubscribedCount++
                    false
                }
                else -> true
            }
        }

        val concurrency = settingsDataStore.settings.first().feedRefreshConcurrency.coerceAtLeast(1)
        val semaphore = Semaphore(concurrency)
        val imported = candidates.map { feed ->
            async { semaphore.withPermit { subscribeIfValid(feed) } }
        }.awaitAll()

        OpmlImportResult(
            importedCount = imported.count { it },
            alreadySubscribedCount = alreadySubscribedCount,
            invalidCount = imported.count { !it },
        )
    }

    /**
     * Candidates run concurrently inside a bare `coroutineScope` (issue #269): an uncaught
     * exception from any one of them would cancel every other in-flight sibling immediately
     * (structured concurrency), interrupting whichever feed a sibling happened to be
     * fetching/persisting mid-operation. Catching broadly here -- on top of
     * [FeedUpdateEngine]'s own [FeedUpdateEngine.persistFetchedFeed] guard -- keeps this one
     * feed's failure from corrupting unrelated feeds in the same import batch, e.g. a DB
     * constraint violation from two different OPML URLs resolving to the same feed after
     * redirects (not caught by the upfront [seenUrls] dedup, which only sees the original URLs).
     */
    private suspend fun subscribeIfValid(feed: OpmlFeed): Boolean = try {
        val result = feedFetcher.fetchFeed(feed.xmlUrl)
        if (result !is FeedFetchResult.Success) {
            false
        } else {
            val id = feedDao.insert(Feed(title = result.feed.title.ifBlank { feed.title }, feedUrl = result.resolvedUrl))
            val newFeed = feedDao.getById(id)
            if (newFeed != null) feedUpdateEngine.persistFetchedFeed(newFeed, result.feed)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
