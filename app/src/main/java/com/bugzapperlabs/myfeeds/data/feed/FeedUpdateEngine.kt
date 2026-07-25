package com.bugzapperlabs.myfeeds.data.feed

import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.UUID
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
) {
    suspend fun updateFeed(feed: Feed): FeedUpdateResult {
        val feedUrl = feed.feedUrl
        if (feedUrl.isNullOrBlank()) return FeedUpdateResult.Failure("Feed has no URL")

        return when (val result = feedFetcher.fetchFeed(feedUrl)) {
            is FeedFetchResult.Failure -> FeedUpdateResult.Failure(result.message)
            is FeedFetchResult.Success -> persist(feed, result.feed)
        }
    }

    suspend fun updateFeeds(feeds: List<Feed>): List<FeedUpdateResult> = coroutineScope {
        val concurrency = settingsDataStore.settings.first().feedRefreshConcurrency.coerceAtLeast(1)
        val semaphore = Semaphore(concurrency)
        feeds.map { feed -> async { semaphore.withPermit { updateFeed(feed) } } }.awaitAll()
    }

    /**
     * Applies an already-fetched [parsed] feed to [feed], skipping a redundant re-fetch. Used by
     * [com.bugzapperlabs.myfeeds.data.opml.OpmlImporter], which must fetch each candidate feed once already
     * to validate it before subscribing (issue #231).
     */
    suspend fun persistFetchedFeed(feed: Feed, parsed: ParsedFeed): FeedUpdateResult = persist(feed, parsed)

    private suspend fun persist(feed: Feed, parsed: ParsedFeed): FeedUpdateResult {
        // A feed's first-ever successful fetch is the only time it's safe to change auto-queue
        // defaults without risking overwriting a value the user has since set themselves via Feed
        // Properties (issue #137) -- lastGet is only ever null before that first fetch completes.
        val isFirstFetch = feed.lastGet == null
        val newItemIds = mutableListOf<String>()
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
                feedRepository.insertItems(listOf(entity))
                newItemIds += id
            } else {
                feedRepository.updateItem(entity)
            }
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
        feedRepository.updateFeed(updatedFeed)
        val defaultItemsToKeep = settingsDataStore.settings.first().maxArticles
        val evicted = feedRepository.trimToItemsToKeep(feed.id, defaultItemsToKeep)

        return FeedUpdateResult.Success(feedId = feed.id, newItemIds = newItemIds, evictedItemIds = evicted.map { it.id })
    }

    companion object {
        private const val NEW_PODCAST_AUTO_QUEUE_CAP = 5
        private const val MAX_DESCRIPTION_LENGTH = 200_000
    }
}
