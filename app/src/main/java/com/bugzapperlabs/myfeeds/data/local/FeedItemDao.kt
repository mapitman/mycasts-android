package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FeedItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FeedItem>)

    @Update
    suspend fun update(item: FeedItem)

    @Delete
    suspend fun delete(item: FeedItem)

    @Delete
    suspend fun deleteAll(items: List<FeedItem>)

    @Query("SELECT * FROM feed_items WHERE feedId = :feedId ORDER BY publishDate DESC")
    fun observeByFeed(feedId: Long): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE feedId = :feedId AND isRead = 0 ORDER BY publishDate DESC")
    fun observeUnreadByFeed(feedId: Long): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE feedId IN (:feedIds) ORDER BY publishDate DESC")
    fun observeByFeeds(feedIds: List<Long>): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE feedId IN (:feedIds) AND isRead = 0 ORDER BY publishDate DESC")
    fun observeUnreadByFeeds(feedIds: List<Long>): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE feedId = :feedId AND itemGuid = :itemGuid LIMIT 1")
    suspend fun findByItemGuid(feedId: Long, itemGuid: String): FeedItem?

    @Query("SELECT * FROM feed_items WHERE id = :id")
    suspend fun getById(id: String): FeedItem?

    @Query("UPDATE feed_items SET enclosurePosition = :position WHERE id = :id")
    suspend fun setEnclosurePosition(id: String, position: Double?)

    @Query("UPDATE feed_items SET isRead = :isRead WHERE id = :id")
    suspend fun setRead(id: String, isRead: Boolean)

    @Query("UPDATE feed_items SET isRead = 1 WHERE feedId = :feedId")
    suspend fun markAllReadForFeed(feedId: Long)

    @Query("SELECT COUNT(*) FROM feed_items WHERE feedId = :feedId AND isRead = 0")
    fun observeUnreadCountForFeed(feedId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM feed_items WHERE feedId IN (:feedIds) AND isRead = 0")
    fun observeUnreadCountForFeeds(feedIds: List<Long>): Flow<Int>

    @Query("SELECT COUNT(*) FROM feed_items WHERE isRead = 0")
    fun observeTotalUnreadCount(): Flow<Int>

    @Query("SELECT feedId, COUNT(*) as count FROM feed_items WHERE isRead = 0 GROUP BY feedId")
    fun observeUnreadCountsByFeed(): Flow<List<FeedUnreadCount>>

    // Podcast-ness (issue #65) is derived, not stored: a feed is a podcast if any of its items
    // has a playable (audio) enclosure, regardless of which category it's subscribed under. Not
    // just "has an enclosure" -- some feeds (e.g. Windows Central, Sky News) set <enclosure> on
    // ordinary articles for a featured image, which isn't a podcast episode (see isPodcastEpisode).
    @Query("SELECT DISTINCT feedId FROM feed_items WHERE enclosureType LIKE 'audio/%'")
    fun observePodcastFeedIds(): Flow<List<Long>>

    @Query("UPDATE feed_items SET enclosurePosition = NULL WHERE enclosurePosition IS NOT NULL")
    suspend fun clearAllEnclosurePositions()

    @Query("UPDATE feed_items SET downloadedBytes = :bytes WHERE id = :id")
    suspend fun setDownloadedBytes(id: String, bytes: Long?)

    @Query("UPDATE feed_items SET downloadedFilePath = :path, downloadedBytes = NULL WHERE id = :id")
    suspend fun setDownloadedFilePath(id: String, path: String?)

    @Query("UPDATE feed_items SET autoDownloaded = :autoDownloaded WHERE id = :id")
    suspend fun setAutoDownloaded(id: String, autoDownloaded: Boolean)

    // Only this feed's auto-downloaded, completed downloads, newest first, as eviction candidates
    // for Feed.maxDownloadsToKeep (issue #250) -- manually-downloaded episodes are never
    // candidates, see FeedItem.autoDownloaded. In-progress downloads (downloadedFilePath still
    // null) aren't candidates either, matching how observeDownloadedItems tells the two apart.
    @Query(
        """
        SELECT * FROM feed_items
        WHERE feedId = :feedId AND autoDownloaded = 1 AND downloadedFilePath IS NOT NULL
        ORDER BY publishDate DESC
        """,
    )
    suspend fun autoDownloadedItemsForFeed(feedId: Long): List<FeedItem>

    // A download in progress has downloadedBytes set (cleared to NULL once downloadedFilePath is
    // set, see setDownloadedFilePath) -- either one present means "has a download" (issue #69).
    @Query(
        """
        SELECT feed_items.*, COALESCE(feeds.userTitle, feeds.title) AS feedTitle
        FROM feed_items
        INNER JOIN feeds ON feeds.id = feed_items.feedId
        WHERE feed_items.downloadedFilePath IS NOT NULL OR feed_items.downloadedBytes IS NOT NULL
        ORDER BY feed_items.publishDate DESC
        """,
    )
    fun observeDownloadedItems(): Flow<List<DownloadedEpisode>>
}
