package com.bugzapperlabs.myfeeds.data.repository

import com.bugzapperlabs.myfeeds.data.local.QueueDao
import com.bugzapperlabs.myfeeds.data.local.QueueEntry
import com.bugzapperlabs.myfeeds.data.local.QueuedEpisode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The "Next Up" playback queue (issue #67), over [QueueDao]. */
class QueueRepository @Inject constructor(
    private val queueDao: QueueDao,
) {
    fun observeQueue(): Flow<List<QueuedEpisode>> = queueDao.observeQueue()

    suspend fun isQueued(itemId: String): Boolean = queueDao.findItemId(itemId) != null

    /**
     * No-op if already queued -- an episode can only be queued once. Returns whether it was added.
     * [autoQueued] should only be true for the feed-refresh auto-queue path (issue #68); manual
     * adds must stay exempt from that feed's [enforceFeedCap] eviction (issue #125/#127).
     */
    suspend fun addToEnd(itemId: String, autoQueued: Boolean = false): Boolean {
        if (queueDao.findItemId(itemId) != null) return false
        queueDao.insert(
            QueueEntry(itemId, position = queueDao.maxPosition() + 1, addedAt = System.currentTimeMillis(), autoQueued = autoQueued),
        )
        return true
    }

    /**
     * No-op if already queued -- an episode can only be queued once. [autoQueued] mirrors
     * [addToEnd]'s parameter of the same name (issue #166: auto-queue can now insert at either end
     * of the queue depending on the feed's `autoQueuePosition`), so this end also needs to be able
     * to mark entries as auto-queued for [enforceFeedCap] eviction to see them.
     */
    suspend fun addToFront(itemId: String, autoQueued: Boolean = false) {
        if (queueDao.findItemId(itemId) != null) return
        queueDao.insert(
            QueueEntry(itemId, position = queueDao.minPosition() - 1, addedAt = System.currentTimeMillis(), autoQueued = autoQueued),
        )
    }

    /** Returns the entry that was removed (for issue #284's undo), or null if it wasn't queued. */
    suspend fun remove(itemId: String): QueueEntry? {
        val entry = queueDao.getEntry(itemId) ?: return null
        queueDao.remove(itemId)
        return entry
    }

    /** Re-inserts an entry previously returned by [remove], undoing it -- a no-op (via the DAO's
     *  IGNORE conflict strategy) if the episode has since been queued again some other way. */
    suspend fun restore(entry: QueueEntry) = queueDao.insert(entry)

    /** Renumbers positions to match [orderedItemIds] exactly, for drag-to-reorder in the queue screen. */
    suspend fun reorder(orderedItemIds: List<String>) {
        orderedItemIds.forEachIndexed { index, itemId -> queueDao.setPosition(itemId, index) }
    }

    /** Removes and returns the item at the front of the queue, or null if the queue is empty. */
    suspend fun popNext(): String? {
        val next = queueDao.firstItemId() ?: return null
        queueDao.remove(next)
        return next
    }

    /**
     * Evicts this feed's oldest *auto-queued* episodes (earliest added, not earliest published)
     * down to [maxCount] (issue #68) -- manually-queued entries are never evicted by this, so a
     * feed auto-queuing new episodes can't silently wipe out ones the user deliberately queued
     * (issue #125/#127). Only removes from the queue; does not touch the episode, its download, or
     * read state.
     */
    suspend fun enforceFeedCap(feedId: Long, maxCount: Int) {
        val ordered = queueDao.orderedAutoQueuedItemIdsForFeed(feedId)
        val excess = ordered.size - maxCount
        if (excess <= 0) return
        ordered.take(excess).forEach { queueDao.remove(it) }
    }
}
