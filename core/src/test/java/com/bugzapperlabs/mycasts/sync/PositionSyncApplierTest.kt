package com.bugzapperlabs.mycasts.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PositionSyncApplierTest {
    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var applier: PositionSyncApplier

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        applier = PositionSyncApplier(feedRepository)

        val feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        feedRepository.insertItems(listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1")))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun apply_writesPositionToLocalItem() = runTest {
        applier.apply(PositionUpdate("ep-1", positionMs = 5_000L, updatedAt = 1_000L))

        assertEquals(5.0, feedRepository.getItem("ep-1")!!.enclosurePosition)
    }

    @Test
    fun apply_olderUpdate_isDropped() = runTest {
        applier.apply(PositionUpdate("ep-1", positionMs = 10_000L, updatedAt = 2_000L))

        applier.apply(PositionUpdate("ep-1", positionMs = 5_000L, updatedAt = 1_000L))

        assertEquals(10.0, feedRepository.getItem("ep-1")!!.enclosurePosition)
    }

    @Test
    fun apply_equalTimestamp_isDropped() = runTest {
        // A duplicate delivery of the same update shouldn't be treated as newer than itself.
        applier.apply(PositionUpdate("ep-1", positionMs = 10_000L, updatedAt = 2_000L))

        applier.apply(PositionUpdate("ep-1", positionMs = 20_000L, updatedAt = 2_000L))

        assertEquals(10.0, feedRepository.getItem("ep-1")!!.enclosurePosition)
    }

    @Test
    fun apply_newerUpdate_overwritesOlder() = runTest {
        applier.apply(PositionUpdate("ep-1", positionMs = 5_000L, updatedAt = 1_000L))

        applier.apply(PositionUpdate("ep-1", positionMs = 10_000L, updatedAt = 2_000L))

        assertEquals(10.0, feedRepository.getItem("ep-1")!!.enclosurePosition)
    }
}
