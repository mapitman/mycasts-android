package com.bugzapperlabs.mycasts.data.repository

import com.bugzapperlabs.mycasts.data.local.DownloadedEpisode
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedDao
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.FeedItemDao
import com.bugzapperlabs.mycasts.data.local.QueueDao
import com.bugzapperlabs.mycasts.data.settings.UNLIMITED_ITEMS_TO_KEEP
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

    /** Clears saved podcast resume positions. Does not touch downloaded files/state (see [com.bugzapperlabs.mycasts.download.DownloadManager]). */
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
     * Consolidates duplicate rows sharing the same `itemGuid` within a feed down to one canonical
     * row per episode (issue #70 follow-up): a since-fixed race between concurrent refreshes of
     * the same feed could insert multiple copies of the same episode, each with its own id -- if
     * any copy got auto-queued, it (and so its whole duplicate group) became permanently exempt
     * from [trimToItemsToKeep]'s eviction, since a queued [FeedItem] is never evicted regardless
     * of count. Called at the start of every persist so a feed already contaminated by that race
     * self-heals on its very next refresh, with no one-off migration or manual cleanup needed.
     *
     * The queued copy (if any) is kept as canonical, so the episode's Next Up membership survives
     * the cleanup; otherwise the most recently published copy is kept (items are already ordered
     * newest-first). The rest are deleted outright.
     */
    suspend fun deduplicateItems(feedId: Long) {
        val items = feedItemDao.observeByFeed(feedId).first()
        val queuedItemIds = queueDao.orderedItemIdsForFeed(feedId).toSet()
        items.filterNot { it.itemGuid.isNullOrBlank() }
            .groupBy { it.itemGuid }
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val canonical = duplicates.firstOrNull { it.id in queuedItemIds } ?: duplicates.first()
                feedItemDao.deleteAll(duplicates.filterNot { it.id == canonical.id })
            }
    }

    /**
     * Deletes the oldest items beyond the feed's `itemsToKeep`, mirroring the original
     * FeedUpdater's trim-by-publish-date behavior. Returns the evicted items so callers can clean
     * up associated enclosure files (issue #12).
     *
     * A feed's `itemsToKeep` of `null` means "use the app-wide default" (see Feed Properties,
     * which falls back to [com.bugzapperlabs.mycasts.data.settings.AppSettings.maxItemsPerFeed] the same way)
     * -- it does NOT mean unlimited, so [defaultItemsToKeep] is required rather than skipping the
     * trim (issue #82: feeds that never had a per-feed override set grew unbounded).
     * [UNLIMITED_ITEMS_TO_KEEP], once resolved, means unlimited (issue #302).
     *
     * Items currently in the Next Up queue are exempt, even if they'd otherwise be old enough to
     * evict -- deleting a queued [FeedItem] cascades to its `queue_entries` row (issue #125:
     * episodes a user had manually queued were being silently dropped from Next Up by this trim).
     *
     * [alwaysExempt] additionally protects specific items regardless of age (issue #83): when a
     * feed publishes more new episodes in one refresh than `itemsToKeep` allows, this trim (called
     * from within [FeedUpdateEngine.persist] right after inserting them) used to run *before*
     * [com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer.apply] -- which runs
     * separately afterward, once the caller has this refresh's full [com.bugzapperlabs.mycasts.data.feed.FeedUpdateResult]
     * -- ever got a chance to auto-download/auto-queue them, silently evicting the oldest of that
     * batch first and leaving the enforcer with nothing but already-deleted item ids to look up.
     * The caller passes this refresh's own newly-inserted item ids here when the feed has
     * auto-download or auto-queue enabled, so they survive long enough for the enforcer to act on
     * them; from the *next* refresh onward they're just ordinary items again, subject to the same
     * trim rules (via the queue exemption above) as anything else.
     */
    suspend fun trimToItemsToKeep(feedId: Long, defaultItemsToKeep: Int, alwaysExempt: Set<String> = emptySet()): List<FeedItem> {
        val itemsToKeep = feedDao.getById(feedId)?.itemsToKeep ?: defaultItemsToKeep
        if (itemsToKeep == UNLIMITED_ITEMS_TO_KEEP) return emptyList()
        val items = feedItemDao.observeByFeed(feedId).first()
        if (items.size <= itemsToKeep) return emptyList()

        val queuedItemIds = queueDao.orderedItemIdsForFeed(feedId).toSet()
        val evicted = items.drop(itemsToKeep).filterNot { it.id in queuedItemIds || it.id in alwaysExempt }
        if (evicted.isEmpty()) return emptyList()
        feedItemDao.deleteAll(evicted)
        return evicted
    }
}
