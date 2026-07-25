package com.bugzapperlabs.myfeeds.data.repository

import com.bugzapperlabs.myfeeds.data.local.DownloadedEpisode
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.FeedItemDao
import com.bugzapperlabs.myfeeds.data.local.QueueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Feed subscription and article state, over [FeedDao]/[FeedItemDao]. Feed update/parsing
 * (issue #11) is a separate concern; this repository only owns persistence.
 */
class FeedRepository @Inject constructor(
    private val feedDao: FeedDao,
    private val feedItemDao: FeedItemDao,
    private val queueDao: QueueDao,
) {
    fun observeAllFeeds(): Flow<List<Feed>> = feedDao.observeAll()

    suspend fun getFeed(feedId: Long): Feed? = feedDao.getById(feedId)

    fun observeFeed(feedId: Long): Flow<Feed?> = feedDao.observeById(feedId)

    suspend fun subscribe(feed: Feed): Long = feedDao.insert(feed)

    suspend fun unsubscribe(feed: Feed) = feedDao.delete(feed)

    suspend fun updateFeed(feed: Feed) = feedDao.update(feed)

    fun observeItems(feedId: Long): Flow<List<FeedItem>> = feedItemDao.observeByFeed(feedId)

    fun observeUnreadItems(feedId: Long): Flow<List<FeedItem>> = feedItemDao.observeUnreadByFeed(feedId)

    fun observeItems(feedIds: List<Long>): Flow<List<FeedItem>> = feedItemDao.observeByFeeds(feedIds)

    fun observeUnreadItems(feedIds: List<Long>): Flow<List<FeedItem>> = feedItemDao.observeUnreadByFeeds(feedIds)

    fun observeUnreadCount(feedId: Long): Flow<Int> = feedItemDao.observeUnreadCountForFeed(feedId)

    fun observeUnreadCount(feedIds: List<Long>): Flow<Int> = feedItemDao.observeUnreadCountForFeeds(feedIds)

    fun observeTotalUnreadCount(): Flow<Int> = feedItemDao.observeTotalUnreadCount()

    fun observeUnreadCountsByFeed(): Flow<Map<Long, Int>> =
        feedItemDao.observeUnreadCountsByFeed().map { counts -> counts.associate { it.feedId to it.count } }

    fun observePodcastFeedIds(): Flow<Set<Long>> = feedItemDao.observePodcastFeedIds().map { it.toSet() }

    suspend fun insertItems(items: List<FeedItem>) = feedItemDao.insertAll(items)

    // Must be a real SQL UPDATE, not insertAll's OnConflictStrategy.REPLACE -- REPLACE does a
    // DELETE+INSERT under the hood even when the row's id is unchanged, which fires
    // queue_entries' ON DELETE CASCADE and silently drops the episode from Next Up on every
    // refresh of an already-queued item (issue #153).
    suspend fun updateItem(item: FeedItem) = feedItemDao.update(item)

    suspend fun findByItemGuid(feedId: Long, itemGuid: String): FeedItem? =
        feedItemDao.findByItemGuid(feedId, itemGuid)

    suspend fun getItem(itemId: String): FeedItem? = feedItemDao.getById(itemId)

    suspend fun setEnclosurePosition(itemId: String, position: Double?) =
        feedItemDao.setEnclosurePosition(itemId, position)

    suspend fun markRead(itemId: String, isRead: Boolean = true) = feedItemDao.setRead(itemId, isRead)

    suspend fun markAllRead(feedId: Long) = feedItemDao.markAllReadForFeed(feedId)

    suspend fun deleteItems(items: List<FeedItem>) = feedItemDao.deleteAll(items)

    suspend fun removeAllFeeds() = feedDao.deleteAll()

    /** Clears saved podcast resume positions. Does not touch downloaded files/state (see [com.bugzapperlabs.myfeeds.download.DownloadManager]). */
    suspend fun clearAllEnclosurePositions() = feedItemDao.clearAllEnclosurePositions()

    suspend fun setDownloadedBytes(itemId: String, bytes: Long?) = feedItemDao.setDownloadedBytes(itemId, bytes)

    suspend fun setDownloadedFilePath(itemId: String, path: String?) = feedItemDao.setDownloadedFilePath(itemId, path)

    suspend fun setAutoDownloaded(itemId: String, autoDownloaded: Boolean) = feedItemDao.setAutoDownloaded(itemId, autoDownloaded)

    /** Every episode with a download in progress or completed, across all feeds (issue #69). */
    fun observeDownloadedItems(): Flow<List<DownloadedEpisode>> = feedItemDao.observeDownloadedItems()

    /** A feed's auto-downloaded, completed episodes, newest first (issue #250) -- see
     *  [FeedItemDao.autoDownloadedItemsForFeed]. */
    suspend fun autoDownloadedItemsForFeed(feedId: Long): List<FeedItem> = feedItemDao.autoDownloadedItemsForFeed(feedId)

    /** A feed's currently-queued item ids, regardless of how they got there (issue #250) --
     *  reused from [trimToItemsToKeep]'s queue exemption for the same purpose against downloads. */
    suspend fun queuedItemIdsForFeed(feedId: Long): Set<String> = queueDao.orderedItemIdsForFeed(feedId).toSet()

    /**
     * Deletes the oldest items beyond the feed's `itemsToKeep`, mirroring the original
     * FeedUpdater's trim-by-publish-date behavior. Returns the evicted items so callers can clean
     * up associated enclosure files (issue #12).
     *
     * A feed's `itemsToKeep` of `null` means "use the app-wide default" (see Feed Properties,
     * which falls back to [com.bugzapperlabs.myfeeds.data.settings.AppSettings.maxArticles] the same way)
     * -- it does NOT mean unlimited, so [defaultItemsToKeep] is required rather than skipping the
     * trim (issue #82: feeds that never had a per-feed override set grew unbounded).
     *
     * Items currently in the Next Up queue are exempt, even if they'd otherwise be old enough to
     * evict -- deleting a queued [FeedItem] cascades to its `queue_entries` row (issue #125:
     * episodes a user had manually queued were being silently dropped from Next Up by this trim).
     */
    suspend fun trimToItemsToKeep(feedId: Long, defaultItemsToKeep: Int): List<FeedItem> {
        val itemsToKeep = feedDao.getById(feedId)?.itemsToKeep ?: defaultItemsToKeep
        val items = feedItemDao.observeByFeed(feedId).first()
        if (items.size <= itemsToKeep) return emptyList()

        val queuedItemIds = queueDao.orderedItemIdsForFeed(feedId).toSet()
        val evicted = items.drop(itemsToKeep).filterNot { it.id in queuedItemIds }
        if (evicted.isEmpty()) return emptyList()
        feedItemDao.deleteAll(evicted)
        return evicted
    }
}
