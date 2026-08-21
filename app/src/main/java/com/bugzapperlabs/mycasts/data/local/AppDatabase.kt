package com.bugzapperlabs.mycasts.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Feed::class, FeedItem::class, QueueEntry::class],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun feedItemDao(): FeedItemDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val NAME = "mycasts.db"
    }
}
