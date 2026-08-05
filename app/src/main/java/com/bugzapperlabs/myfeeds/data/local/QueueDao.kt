package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: QueueEntry)

    @Query("DELETE FROM queue_entries WHERE itemId = :itemId")
    suspend fun remove(itemId: String)

    @Query("DELETE FROM queue_entries")
    suspend fun clear()

    @Query("SELECT COALESCE(MIN(position), 0) FROM queue_entries")
    suspend fun minPosition(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue_entries")
    suspend fun maxPosition(): Int

    @Query("SELECT itemId FROM queue_entries WHERE itemId = :itemId LIMIT 1")
    suspend fun findItemId(itemId: String): String?

    // Fetched before a remove (issue #284) so the exact entry -- position, addedAt, autoQueued --
    // can be handed back to insert() unchanged if the removal is undone.
    @Query("SELECT * FROM queue_entries WHERE itemId = :itemId LIMIT 1")
    suspend fun getEntry(itemId: String): QueueEntry?

    @Query("SELECT itemId FROM queue_entries ORDER BY position")
    suspend fun orderedItemIds(): List<String>

    @Query("SELECT itemId FROM queue_entries ORDER BY position LIMIT 1")
    suspend fun firstItemId(): String?

    @Query("UPDATE queue_entries SET position = :position WHERE itemId = :itemId")
    suspend fun setPosition(itemId: String, position: Int)

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
    suspend fun orderedItemIdsForFeed(feedId: Long): List<String>

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
    suspend fun orderedAutoQueuedItemIdsForFeed(feedId: Long): List<String>

    @Query(
        """
        SELECT feed_items.*, COALESCE(feeds.userTitle, feeds.title) AS feedTitle, feeds.imageUrl AS feedImageUrl
        FROM queue_entries
        INNER JOIN feed_items ON feed_items.id = queue_entries.itemId
        INNER JOIN feeds ON feeds.id = feed_items.feedId
        ORDER BY queue_entries.position
        """,
    )
    fun observeQueue(): Flow<List<QueuedEpisode>>
}
