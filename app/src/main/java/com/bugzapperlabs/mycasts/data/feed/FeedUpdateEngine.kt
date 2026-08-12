package com.bugzapperlabs.mycasts.data.feed

import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.AppSettings
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Ported from FeedManager.UpdateFeed's persistence half (fetch/parse is FeedFetcher, #10):
 * dedup incoming items by itemGuid (existing items keep their id/isRead/enclosurePosition, new
 * items get a fresh UUID), extract each item's first image, trim to itemsToKeep, and update the
 * feed's lastGet. Multiple feeds refresh with bounded concurrency, mirroring the original's
 * intent to not hammer the network/DB with unlimited parallel requests -- user-configurable
 * (issue #177) via [SettingsDataStore.settings]' `feedRefreshConcurrency`.
 */
class FeedUpdateEngine @Inject constructor(
    private val feedFetcher: FeedFetcher,
    private val feedRepository: FeedRepository,
    private val settingsDataStore: SettingsDataStore,
    private val feedRefreshLocks: FeedRefreshLocks,
) {
    suspend fun updateFeed(feed: Feed): FeedUpdateResult {
        val feedUrl = feed.feedUrl
        if (feedUrl.isNullOrBlank()) return FeedUpdateResult.Failure("Feed has no URL")

        return when (val result = feedFetcher.fetchFeed(feedUrl)) {
            is FeedFetchResult.Failure -> FeedUpdateResult.Failure(result.message)
            is FeedFetchResult.Success -> persistSafely(feed, result.feed, settingsDataStore.settings.first())
        }
    }

    /**
     * [onFeedComplete] (issue #16), if given, is invoked once per feed as its update finishes,
     * with the running count of feeds completed so far -- calls can arrive out of order relative
     * to [feeds] and interleaved across concurrent feeds, since updates run with bounded
     * parallelism above, so [completedCount] is tracked with an atomic counter rather than a
     * plain incrementing var.
     */
    suspend fun updateFeeds(
        feeds: List<Feed>,
        onFeedComplete: suspend (completedCount: Int, totalCount: Int) -> Unit = { _, _ -> },
    ): List<FeedUpdateResult> = coroutineScope {
        // Resolved once for the whole batch, not per feed (issue #106): every concurrent feed in
        // this batch used to call settingsDataStore.settings.first() itself from inside persist(),
        // piling up concurrent readers of the same cold Flow<AppSettings> right as a large feed's
        // whole item-insert loop finished -- reproducibly hanging indefinitely on the largest feed
        // in a many-feed OPML import, with every coroutine dispatcher thread sitting idle.
        val settings = settingsDataStore.settings.first()
        val semaphore = Semaphore(settings.feedRefreshConcurrency.coerceAtLeast(1))
        val completedCount = AtomicInteger(0)
        feeds.map { feed ->
            async {
                val result = semaphore.withPermit { updateFeedWithSettings(feed, settings) }
                onFeedComplete(completedCount.incrementAndGet(), feeds.size)
                result
            }
        }.awaitAll()
    }

    private suspend fun updateFeedWithSettings(feed: Feed, settings: AppSettings): FeedUpdateResult {
        val feedUrl = feed.feedUrl
        if (feedUrl.isNullOrBlank()) return FeedUpdateResult.Failure("Feed has no URL")

        return when (val result = feedFetcher.fetchFeed(feedUrl)) {
            is FeedFetchResult.Failure -> FeedUpdateResult.Failure(result.message)
            is FeedFetchResult.Success -> persistSafely(feed, result.feed, settings)
        }
    }

    /**
     * Applies an already-fetched [parsed] feed to [feed], skipping a redundant re-fetch. Used by
     * [com.bugzapperlabs.mycasts.data.opml.OpmlImporter], which must fetch each candidate feed once already
     * to validate it before subscribing (issue #231) -- [settings] is likewise resolved once by
     * the importer for its whole batch rather than per feed here, for the same reason as
     * [updateFeeds] above.
     */
    suspend fun persistFetchedFeed(feed: Feed, parsed: ParsedFeed, settings: AppSettings): FeedUpdateResult =
        persistSafely(feed, parsed, settings)

    /**
     * [persist] runs concurrently across many feeds -- both here via [updateFeeds] and in
     * [com.bugzapperlabs.mycasts.data.opml.OpmlImporter] -- inside a bare `coroutineScope`, where
     * an uncaught exception from any one feed cancels every other concurrently in-flight sibling
     * immediately (structured concurrency), interrupting them mid-`persist` (issue #269): a sibling
     * could end up with items inserted but the trim-to-`itemsToKeep` step -- or even `Feed.lastGet`
     * -- never reached. Catching broadly here, rather than only the specific exceptions seen so
     * far, keeps one feed's failure (present or future) from corrupting unrelated feeds' state.
     *
     * [FeedRefreshLocks] additionally guards against *the same feed* being persisted twice at once
     * (issue #70) -- e.g. a manual pull-to-refresh landing while the scheduled
     * [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorker] is also refreshing that feed -- which
     * otherwise duplicates episodes and lets the itemsToKeep trim fall badly behind.
     */
    private suspend fun persistSafely(feed: Feed, parsed: ParsedFeed, settings: AppSettings): FeedUpdateResult = try {
        feedRefreshLocks.withLock(feed.id) { persist(feed, parsed, settings) }
    } catch (e: CancellationException) {
        // Genuine cancellation (e.g. the caller's own scope was cancelled) must keep propagating
        // -- only *other* feeds' failures should be contained, not this one's real cancellation.
        throw e
    } catch (e: Exception) {
        FeedUpdateResult.Failure(e.message ?: "Failed to save feed")
    }

    private suspend fun persist(feed: Feed, parsed: ParsedFeed, settings: AppSettings): FeedUpdateResult {
        // A feed's first-ever successful fetch is the only time it's safe to change auto-queue
        // defaults without risking overwriting a value the user has since set themselves via Feed
        // Properties (issue #137) -- lastGet is only ever null before that first fetch completes.
        val isFirstFetch = feed.lastGet == null
        val newItemIds = mutableListOf<String>()
        val newItems = mutableListOf<FeedItem>()
        var hasPodcastEpisode = false

        parsed.items.forEach { parsedItem ->
            val itemGuid = parsedItem.itemGuid.ifBlank { parsedItem.url }
            if (itemGuid.isBlank()) return@forEach

            if (parsedItem.enclosure?.type?.startsWith("audio/", ignoreCase = true) == true) {
                hasPodcastEpisode = true
            }

            val existing = feedRepository.findByItemGuid(feed.id, itemGuid)
            val firstImage = FirstImageExtractor.extractAndStripFirstImage(parsedItem.description, parsedItem.url)
            val id = existing?.id ?: UUID.randomUUID().toString()

            val entity = FeedItem(
                id = id,
                feedId = feed.id,
                title = parsedItem.title,
                // Capped well under Android's ~2MB CursorWindow-per-row limit (issue #234) -- a
                // handful of feeds publish long-form full-text posts large enough on their own to
                // make a single row too big for Room to read back, crashing the app. The item's own
                // url still lets the user read the rest on the web if it's truncated.
                description = firstImage.descriptionWithoutImage.take(MAX_DESCRIPTION_LENGTH),
                url = parsedItem.url,
                imageUrl = firstImage.url,
                itemGuid = itemGuid,
                publishDate = parsedItem.publishDate?.toEpochMilli(),
                isRead = existing?.isRead ?: false,
                enclosureUrl = parsedItem.enclosure?.url,
                enclosureType = parsedItem.enclosure?.type,
                enclosureLength = parsedItem.enclosure?.length,
                enclosurePosition = existing?.enclosurePosition,
                enclosureDurationMs = parsedItem.durationMs,
                chaptersUrl = parsedItem.chaptersUrl,
            )
            if (existing == null) {
                newItems += entity
                newItemIds += id
            } else {
                feedRepository.updateItem(entity)
            }
        }
        // Batched into a single bulk insert rather than one insertItems call per new item (issue
        // #106): a feed publishing thousands of episodes at once (e.g. a first-time import of a
        // large back catalog) otherwise opens and commits one Room transaction per item,
        // serialized against every other feed refreshing concurrently through Room's single
        // writer -- reproducibly hanging indefinitely partway through the largest feed in a
        // many-feed import. Mirrors how other feed-sync apps batch new-episode inserts.
        if (newItems.isNotEmpty()) {
            feedRepository.insertItems(newItems)
        }

        // Re-fetched right before the write rather than reusing the [feed] snapshot passed in at
        // the start of this refresh (issue #189): a fetch can take long enough for the user to
        // change something else on this feed meanwhile (e.g. playback speed via the player) --
        // writing back a Feed built from the stale snapshot would silently clobber that edit.
        val currentFeed = feedRepository.getFeed(feed.id) ?: feed
        // Backfills a title left blank at subscribe time -- e.g. an OPML outline with no title/text
        // attribute (issue #219) -- from the feed's own <title> once it's actually fetched.
        val title = if (currentFeed.title.isNullOrBlank() && parsed.title.isNotBlank()) parsed.title else currentFeed.title
        var updatedFeed = currentFeed.copy(
            title = title,
            lastGet = Instant.now().toEpochMilli(),
            imageUrl = parsed.imageUrl ?: currentFeed.imageUrl,
        )
        // New podcast subscriptions default to auto-queuing, capped at a small number of episodes
        // rather than unlimited (issue #137), so Next Up doesn't get flooded by a feed's entire
        // back catalog on the very first fetch.
        if (isFirstFetch && hasPodcastEpisode && !currentFeed.autoQueueEnabled) {
            updatedFeed = updatedFeed.copy(autoQueueEnabled = true, autoQueueMaxCount = NEW_PODCAST_AUTO_QUEUE_CAP)
        }
        // Global default for a new feed's own auto-download toggle (issue #98), mirroring the
        // auto-queue default above -- a per-feed, one-time default, not a master switch, so it
        // never touches a feed that's already been through its first fetch. Also carries over the
        // global max-downloads-to-keep default, the same way Feed Properties' own auto-download
        // toggle pairs with its own max-count chips, rather than leaving maxDownloadsToKeep at
        // whatever Feed's own class default happens to be.
        if (isFirstFetch && hasPodcastEpisode && !currentFeed.autoDownloadEnabled && settings.autoDownloadNewFeedsByDefault) {
            updatedFeed = updatedFeed.copy(autoDownloadEnabled = true, maxDownloadsToKeep = settings.autoDownloadNewFeedsMaxCount)
        }
        feedRepository.updateFeed(updatedFeed)
        // Self-heals any lingering duplicate episodes from the since-fixed concurrent-refresh race
        // (issue #70 follow-up) before trimming, so a feed already contaminated by it recovers on
        // its own next refresh.
        feedRepository.deduplicateItems(feed.id)
        val defaultItemsToKeep = settings.maxItemsPerFeed
        // Protects this refresh's own new items from same-refresh eviction when the enforcer is
        // guaranteed to process them right after (issue #83) -- see trimToItemsToKeep's doc.
        val protectedNewItemIds = if (updatedFeed.autoDownloadEnabled || updatedFeed.autoQueueEnabled) {
            newItemIds.toSet()
        } else {
            emptySet()
        }
        val evicted = feedRepository.trimToItemsToKeep(feed.id, defaultItemsToKeep, protectedNewItemIds)

        return FeedUpdateResult.Success(feedId = feed.id, newItemIds = newItemIds, evictedItemIds = evicted.map { it.id })
    }

    companion object {
        private const val NEW_PODCAST_AUTO_QUEUE_CAP = 5
        private const val MAX_DESCRIPTION_LENGTH = 200_000
    }
}
