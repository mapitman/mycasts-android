package com.bugzapperlabs.mycasts.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Insert
    suspend fun insert(feed: Feed): Long

    /** Applying a Wear OS sync payload (issue #276): unlike [insert]/[insertAll], repeat syncs of
     *  the same [Feed.id] are expected (the watch re-receives the whole Next Up snapshot on every
     *  phone-side queue change), so this replaces rather than aborting on conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feed: Feed)

    @Update
    suspend fun update(feed: Feed)

    @Delete
    suspend fun delete(feed: Feed)

    @Query("SELECT * FROM feeds ORDER BY sortOrder, title")
    fun observeAll(): Flow<List<Feed>>

    /** A one-shot snapshot of every feed (issue #157), for a full backup export -- unlike
     *  [observeAll], which is a live subscription callers would otherwise have to remember to
     *  cancel. */
    @Query("SELECT * FROM feeds")
    suspend fun getAll(): List<Feed>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun getById(id: Long): Feed?

    @Query("SELECT * FROM feeds WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeedUrl(feedUrl: String): Feed?

    @Query("SELECT * FROM feeds WHERE id = :id")
    fun observeById(id: Long): Flow<Feed?>
}
