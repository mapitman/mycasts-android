package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Insert
    suspend fun insert(feed: Feed): Long

    @Update
    suspend fun update(feed: Feed)

    @Delete
    suspend fun delete(feed: Feed)

    @Query("SELECT * FROM feeds ORDER BY sortOrder, title")
    fun observeAll(): Flow<List<Feed>>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun getById(id: Long): Feed?

    @Query("SELECT * FROM feeds WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeedUrl(feedUrl: String): Feed?

    @Query("SELECT * FROM feeds WHERE id = :id")
    fun observeById(id: Long): Flow<Feed?>

    @Query("DELETE FROM feeds")
    suspend fun deleteAll()
}
