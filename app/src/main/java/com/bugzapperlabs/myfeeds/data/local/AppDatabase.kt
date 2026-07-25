package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Feed::class, FeedItem::class, QueueEntry::class],
    version = 13,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun feedItemDao(): FeedItemDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val NAME = "myfeeds.db"
    }
}
