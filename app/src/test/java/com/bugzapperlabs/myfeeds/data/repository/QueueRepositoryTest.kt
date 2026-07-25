package com.bugzapperlabs.myfeeds.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private var feedId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())

        feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1"),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "g2"),
                FeedItem(id = "ep-3", feedId = feedId, title = "Episode 3", itemGuid = "g3"),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addToEnd_appendsInOrder() = runTest {
        assertTrue(queueRepository.addToEnd("ep-1"))
        assertTrue(queueRepository.addToEnd("ep-2"))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToEnd_alreadyQueued_isNoOp() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        assertFalse(queueRepository.addToEnd("ep-1"))
        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToFront_insertsBeforeExisting() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        queueRepository.addToFront("ep-3")

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-3", "ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToFront_autoQueuedTrue_isEvictionCandidate() = runTest {
        // issue #166: addToFront needs its own autoQueued flag, matching addToEnd, so front-inserted
        // auto-queue entries are still subject to a feed's autoQueueMaxCount eviction.
        queueRepository.addToFront("ep-1", autoQueued = true)
        queueRepository.addToFront("ep-2", autoQueued = true)

        queueRepository.enforceFeedCap(feedId, maxCount = 1)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToFront_defaultsToNotAutoQueued() = runTest {
        queueRepository.addToFront("ep-1")

        queueRepository.enforceFeedCap(feedId, maxCount = 0)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1"), queue.map { it.item.id })
    }

    @Test
    fun remove_dropsItemAndKeepsRestInOrder() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")
        queueRepository.addToEnd("ep-3")

        queueRepository.remove("ep-2")

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-3"), queue.map { it.item.id })
    }

    @Test
    fun reorder_renumbersToMatchGivenOrder() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")
        queueRepository.addToEnd("ep-3")

        queueRepository.reorder(listOf("ep-3", "ep-1", "ep-2"))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-3", "ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun popNext_removesAndReturnsFrontOfQueue() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        val next = queueRepository.popNext()

        assertEquals("ep-1", next)
        assertEquals(listOf("ep-2"), queueRepository.observeQueue().first().map { it.item.id })
    }

    @Test
    fun popNext_emptyQueue_returnsNull() = runTest {
        assertNull(queueRepository.popNext())
    }

    @Test
    fun isQueued_reflectsCurrentState() = runTest {
        assertFalse(queueRepository.isQueued("ep-1"))

        queueRepository.addToEnd("ep-1")

        assertTrue(queueRepository.isQueued("ep-1"))
    }

    @Test
    fun observeQueue_includesFeedTitle() = runTest {
        queueRepository.addToEnd("ep-1")

        val queue = queueRepository.observeQueue().first()

        assertEquals("A Feed", queue.single().feedTitle)
    }

    @Test
    fun unsubscribingFeed_cascadesRemovalFromQueue() = runTest {
        queueRepository.addToEnd("ep-1")
        val feed = feedRepository.getFeed(feedId)!!

        feedRepository.unsubscribe(feed)

        assertTrue(queueRepository.observeQueue().first().isEmpty())
    }

    @Test
    fun enforceFeedCap_evictsOldestAutoQueuedFromThatFeedOnly() = runTest {
        val otherFeedId = feedRepository.subscribe(Feed(title = "Other Feed"))
        feedRepository.insertItems(listOf(FeedItem(id = "other-1", feedId = otherFeedId, title = "Other Episode", itemGuid = "og1")))
        queueRepository.addToEnd("ep-1", autoQueued = true)
        queueRepository.addToEnd("ep-2", autoQueued = true)
        queueRepository.addToEnd("ep-3", autoQueued = true)
        queueRepository.addToEnd("other-1", autoQueued = true)

        queueRepository.enforceFeedCap(feedId, maxCount = 1)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-3", "other-1"), queue.map { it.item.id })
    }

    @Test
    fun enforceFeedCap_underCap_isNoOp() = runTest {
        queueRepository.addToEnd("ep-1", autoQueued = true)
        queueRepository.addToEnd("ep-2", autoQueued = true)

        queueRepository.enforceFeedCap(feedId, maxCount = 5)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun enforceFeedCap_ordersByAddedAtNotPosition_whenAutoQueuingToFront() = runTest {
        // issue #166: a feed that auto-queues to the front of Next Up ends up with its earliest
        // auto-queued episode at the *highest* position, not the lowest -- eviction must key off
        // addedAt (queuing order) rather than position to still evict the actually-oldest one.
        queueRepository.addToFront("ep-1", autoQueued = true) // queued first, ends up last in position
        queueRepository.addToFront("ep-2", autoQueued = true) // queued second, ends up first in position

        queueRepository.enforceFeedCap(feedId, maxCount = 1)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-2"), queue.map { it.item.id })
    }

    @Test
    fun enforceFeedCap_neverEvictsManuallyQueuedEntries() = runTest {
        // issue #125/#127: a feed auto-queuing new episodes shouldn't silently wipe out episodes
        // the user deliberately added to Next Up.
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")
        queueRepository.addToEnd("ep-3", autoQueued = true)

        queueRepository.enforceFeedCap(feedId, maxCount = 0)

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }
}
