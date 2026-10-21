package com.bugzapperlabs.mycasts.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class QueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entry: QueueEntry)

    /** Restoring a full backup's queue order (issue #157) in one call, rather than [insert]-ing
     *  one entry at a time. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(entries: List<QueueEntry>)

    @Query("DELETE FROM queue_entries WHERE itemId = :itemId")
    abstract suspend fun remove(itemId: String)

    @Query("DELETE FROM queue_entries")
    abstract suspend fun clear()

    /** A one-shot snapshot of the whole queue in position order (issue #157), for a full backup
     *  export. */
    @Query("SELECT * FROM queue_entries ORDER BY position")
    abstract suspend fun getAll(): List<QueueEntry>

    @Query("SELECT COALESCE(MIN(position), 0) FROM queue_entries")
    abstract suspend fun minPosition(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue_entries")
    abstract suspend fun maxPosition(): Int

    @Query("SELECT itemId FROM queue_entries WHERE itemId = :itemId LIMIT 1")
    abstract suspend fun findItemId(itemId: String): String?

    // Fetched before a remove (issue #284) so the exact entry -- position, addedAt, autoQueued --
    // can be handed back to insert() unchanged if the removal is undone.
    @Query("SELECT * FROM queue_entries WHERE itemId = :itemId LIMIT 1")
    abstract suspend fun getEntry(itemId: String): QueueEntry?

    @Query("SELECT itemId FROM queue_entries ORDER BY position")
    abstract suspend fun orderedItemIds(): List<String>

    @Query("SELECT itemId FROM queue_entries ORDER BY position LIMIT 1")
    abstract suspend fun firstItemId(): String?

    @Query("UPDATE queue_entries SET position = :position WHERE itemId = :itemId")
    abstract suspend fun setPosition(itemId: String, position: Int)

    // All of a feed's currently-queued items regardless of how they got there, oldest-position
    // first -- used to exempt queued items from item-count trimming (issue #125).
    @Query(
        """
        SELECT queue_entries.itemId FROM queue_entries
        INNER JOIN feed_items ON feed_items.id = queue_entries.itemId
        WHERE feed_items.feedId = :feedId
        ORDER BY queue_entries.position
        """,
    )
    abstract suspend fun orderedItemIdsForFeed(feedId: Long): List<String>

    // Only this feed's auto-queued items, oldest-queued first, for per-feed cap eviction (issue
    // #68) -- manually-queued entries are never candidates, see [QueueEntry.autoQueued]
    // (issue #125/#127). Ordered by addedAt rather than position (issue #166): once auto-queue can
    // insert at the front of the queue as well as the end, position no longer reliably tracks
    // queuing order -- the oldest auto-queued item can end up at either end depending on which way
    // its feed inserts -- but addedAt always does.
    @Query(
        """
        SELECT queue_entries.itemId FROM queue_entries
        INNER JOIN feed_items ON feed_items.id = queue_entries.itemId
        WHERE feed_items.feedId = :feedId AND queue_entries.autoQueued = 1
        ORDER BY queue_entries.addedAt
        """,
    )
    abstract suspend fun orderedAutoQueuedItemIdsForFeed(feedId: Long): List<String>

    @Query(
        """
        SELECT feed_items.*, COALESCE(feeds.userTitle, feeds.title) AS feedTitle, feeds.imageUrl AS feedImageUrl
        FROM queue_entries
        INNER JOIN feed_items ON feed_items.id = queue_entries.itemId
        INNER JOIN feeds ON feeds.id = feed_items.feedId
        ORDER BY queue_entries.position
        """,
    )
    abstract fun observeQueue(): Flow<List<QueuedEpisode>>

    /** Just the queued item IDs (issue #52), cheaper than [observeQueue] for screens that only
     *  need to know whether a given episode is queued, not the full queue itself. */
    @Query("SELECT itemId FROM queue_entries")
    abstract fun observeQueuedItemIds(): Flow<List<String>>

    // The methods below combine several statements that must not interleave with another writer
    // (issue #298): a concurrent add could otherwise read the same max/min position, and an
    // observer could see a half-renumbered or briefly empty queue.

    /** Appends [itemId] at the end unless already queued. Returns whether it was added. */
    @Transaction
    open suspend fun appendIfAbsent(itemId: String, addedAt: Long, autoQueued: Boolean): Boolean {
        if (findItemId(itemId) != null) return false
        insert(QueueEntry(itemId, position = maxPosition() + 1, addedAt = addedAt, autoQueued = autoQueued))
        return true
    }

    /** Inserts [itemId] at the front, or directly after [afterItemId] when it is given, unless
     *  already queued. */
    @Transaction
    open suspend fun prependIfAbsent(itemId: String, addedAt: Long, autoQueued: Boolean, afterItemId: String?): Boolean {
        if (findItemId(itemId) != null) return false
        insert(QueueEntry(itemId, position = minPosition() - 1, addedAt = addedAt, autoQueued = autoQueued))
        if (afterItemId != null && findItemId(afterItemId) != null) {
            val ordered = orderedItemIds().toMutableList()
            ordered.remove(itemId)
            ordered.add(ordered.indexOf(afterItemId) + 1, itemId)
            reorder(ordered)
        }
        return true
    }

    /** Moves [itemId] to the front if queued, otherwise inserts it there. */
    @Transaction
    open suspend fun moveToFront(itemId: String, addedAt: Long) {
        val position = minPosition() - 1
        if (findItemId(itemId) != null) {
            setPosition(itemId, position)
        } else {
            insert(QueueEntry(itemId, position = position, addedAt = addedAt))
        }
    }

    /** Moves [itemId] to the back if queued; a no-op otherwise. */
    @Transaction
    open suspend fun moveToEnd(itemId: String) {
        if (findItemId(itemId) == null) return
        setPosition(itemId, maxPosition() + 1)
    }

    /** Renumbers positions to match [orderedItemIds] exactly. */
    @Transaction
    open suspend fun reorder(orderedItemIds: List<String>) {
        orderedItemIds.forEachIndexed { index, itemId -> setPosition(itemId, index) }
    }

    /** Replaces the whole queue with [entries]. */
    @Transaction
    open suspend fun replaceAll(entries: List<QueueEntry>) {
        clear()
        insertAll(entries)
    }
}
