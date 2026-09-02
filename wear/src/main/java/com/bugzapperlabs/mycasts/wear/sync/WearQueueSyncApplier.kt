package com.bugzapperlabs.mycasts.wear.sync

import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.sync.SyncQueueItem
import javax.inject.Inject

/**
 * Applies an incoming Next Up snapshot from the phone to the watch's own local database (issue
 * #276) -- the watch's [QueueRepository]/[FeedRepository] are a projection of the phone's, kept
 * current only by this, never by independent feed refresh or user edits.
 */
class WearQueueSyncApplier @Inject constructor(
    private val feedRepository: FeedRepository,
    private val queueRepository: QueueRepository,
) {
    suspend fun apply(queue: List<SyncQueueItem>) {
        // Each referenced feed's row must exist first -- feed_items.feedId is a FOREIGN KEY onto
        // feeds.id, so inserting a FeedItem before its parent Feed row exists would fail.
        queue.distinctBy { it.feedId }.forEach { syncItem ->
            feedRepository.upsertSyncedFeed(
                Feed(id = syncItem.feedId, title = syncItem.feedTitle, imageUrl = syncItem.artworkUrl),
            )
        }
        feedRepository.insertItems(queue.map { it.toFeedItem() })
        val entries = queue.sortedBy { it.orderIndex }.map { syncItem ->
            QueueEntry(itemId = syncItem.itemId, position = syncItem.orderIndex, addedAt = System.currentTimeMillis())
        }
        queueRepository.replaceQueueFromSync(entries)
    }
}

private fun SyncQueueItem.toFeedItem(): FeedItem = FeedItem(
    id = itemId,
    feedId = feedId,
    title = title,
    imageUrl = artworkUrl,
    enclosureUrl = enclosureUrl,
    // A non-null audio/* enclosureType is what FeedItem.isPodcastEpisode checks for -- every
    // synced item is by definition a playable episode already, streaming directly from
    // enclosureUrl on the watch's own connection.
    enclosureType = enclosureUrl?.let { "audio/*" },
    enclosureDurationMs = durationMs,
    enclosurePosition = positionMs?.let { it / 1000.0 },
)
