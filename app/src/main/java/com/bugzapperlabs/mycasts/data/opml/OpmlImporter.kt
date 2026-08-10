package com.bugzapperlabs.mycasts.data.opml

import com.bugzapperlabs.mycasts.data.feed.FeedFetchResult
import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedDao
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
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
 * - inserts every remaining candidate into the database up front, before any of them are fetched
 *   (issue #50) -- so a large OPML file's feeds all appear in the feed list immediately, rather
 *   than trickling in one at a time as each one's own network fetch happens to finish;
 * - validates each one by actually fetching it, deleting it again if the fetch fails so a
 *   dead/broken URL doesn't stick around as a permanently-blank feed (issue #231);
 * - populates each subscribed feed's title/items from that same fetch, rather than leaving it
 *   blank until the next scheduled refresh (issue #230) -- the fetched channel title wins over
 *   the OPML outline's title, unless the fetch left it blank.
 *
 * Fetches run with the same bounded concurrency as a normal feed refresh
 * ([FeedUpdateEngine.updateFeeds]), so a large OPML file doesn't hammer the network with
 * unbounded parallel requests -- but the up-front inserts above are not gated by that concurrency
 * limit, since they don't touch the network.
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

        val inserted = candidates.map { feed ->
            val newFeed = Feed(title = feed.title, feedUrl = feed.xmlUrl, description = feed.description)
            feed to newFeed.copy(id = feedDao.insert(newFeed))
        }

        val concurrency = settingsDataStore.settings.first().feedRefreshConcurrency.coerceAtLeast(1)
        val semaphore = Semaphore(concurrency)
        val imported = inserted.map { (feed, feedRow) ->
            async { semaphore.withPermit { validateAndPersist(feed, feedRow) } }
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
    private suspend fun validateAndPersist(feed: OpmlFeed, feedRow: Feed): Boolean = try {
        val result = feedFetcher.fetchFeed(feed.xmlUrl)
        if (result !is FeedFetchResult.Success) {
            feedDao.delete(feedRow)
            false
        } else {
            // xmlUrl can redirect to a different final URL (e.g. http -> https) -- saved here
            // rather than left as the original xmlUrl, same as the pre-#50 insert-after-fetch
            // behavior that stored result.resolvedUrl directly. Title is also resolved explicitly
            // here (fetched channel title wins over the OPML outline's, unless the fetch left it
            // blank) rather than relying on FeedUpdateEngine's own title-backfill, which favors
            // whatever title a feed already has -- the OPML outline title in this case -- and would
            // otherwise reverse this importer's pre-#50 title preference.
            val withResolvedUrl = feedRow.copy(
                feedUrl = result.resolvedUrl,
                title = result.feed.title.ifBlank { feed.title },
            )
            feedDao.update(withResolvedUrl)
            feedUpdateEngine.persistFetchedFeed(withResolvedUrl, result.feed)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        feedDao.delete(feedRow)
        false
    }
}
