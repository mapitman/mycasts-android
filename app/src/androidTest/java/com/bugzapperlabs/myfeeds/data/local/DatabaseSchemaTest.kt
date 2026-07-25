package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertFeedAndFeedItem_readsBackViaFlows() = runBlocking {
        val feedId = db.feedDao().insert(Feed(title = "Windows Phone Blog", feedUrl = "https://example.com/feed"))
        db.feedItemDao().insert(
            FeedItem(id = "item-1", feedId = feedId, title = "Hello world", itemGuid = "guid-1"),
        )

        assertEquals(listOf(feedId), db.feedDao().observeAll().first().map { it.id })
        assertEquals(1, db.feedItemDao().observeByFeed(feedId).first().size)
        assertEquals(1, db.feedItemDao().observeUnreadCountForFeed(feedId).first())
        assertEquals(1, db.feedItemDao().observeTotalUnreadCount().first())
    }

    @Test
    fun deletingFeed_cascadesToFeedItems() = runBlocking {
        val feed = Feed(title = "A Feed")
        val feedId = db.feedDao().insert(feed)
        db.feedItemDao().insert(FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1"))

        db.feedDao().delete(feed.copy(id = feedId))

        assertNull(db.feedDao().getById(feedId))
        assertEquals(0, db.feedItemDao().observeByFeed(feedId).first().size)
    }

    @Test
    fun findByItemGuid_dedupesWithinFeed() = runBlocking {
        val feedId = db.feedDao().insert(Feed(title = "A Feed"))
        db.feedItemDao().insert(FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1"))

        val found = db.feedItemDao().findByItemGuid(feedId, "guid-1")

        assertEquals("item-1", found?.id)
    }

    @Test
    fun setRead_updatesUnreadCounts() = runBlocking {
        val feedId = db.feedDao().insert(Feed(title = "A Feed"))
        db.feedItemDao().insert(FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1"))

        db.feedItemDao().setRead("item-1", true)

        assertEquals(0, db.feedItemDao().observeUnreadCountForFeed(feedId).first())
    }
}
