package com.bugzapperlabs.mycasts.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Backup restore (issue #157) as one transaction (issue #298): a failure partway through rolls
 * back to the previous data instead of leaving the user with every feed already deleted and only
 * part of the backup inserted.
 */
@Dao
abstract class BackupDao {
    // FeedItem/QueueEntry rows cascade-delete along with their parent feed, so this alone clears
    // the whole database.
    @Query("DELETE FROM feeds")
    protected abstract suspend fun deleteAllFeeds()

    // Each feed's original id is preserved: the backup's items and queue entries reference it.
    @Insert
    protected abstract suspend fun insertFeeds(feeds: List<Feed>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertItems(items: List<FeedItem>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertQueueEntries(entries: List<QueueEntry>)

    @Transaction
    open suspend fun replaceAll(feeds: List<Feed>, items: List<FeedItem>, queueEntries: List<QueueEntry>) {
        deleteAllFeeds()
        insertFeeds(feeds)
        insertItems(items)
        insertQueueEntries(queueEntries)
    }
}
