package com.bugzapperlabs.mycasts.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private var feedId: Long = 0
    private val enqueuedDownloadItemIds = mutableListOf<String>()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        val downloadTrigger = object : QueueDownloadTrigger {
            override suspend fun ensureDownloaded(item: FeedItem) {
                enqueuedDownloadItemIds += item.id
            }
        }
        queueRepository = QueueRepository(db.queueDao(), feedRepository, downloadTrigger, settingsDataStore)

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
    fun addToFront_currentlyPlaying_insertsAfterItRatherThanDisplacingIt() = runTest {
        // issue #271: "front" means directly after the currently-playing episode, not the
        // absolute front of the queue -- moveToFront pins the playing episode at the true front so
        // it's still shown as "now playing" in Next Up, and a lower-positioned front-queue insert
        // would silently knock it out of that slot.
        queueRepository.addToEnd("ep-1")
        queueRepository.moveToFront("ep-1")
        settingsDataStore.setLastPlayingItem(feedId, "ep-1")

        queueRepository.addToFront("ep-2")

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToFront_currentlyPlaying_multipleFrontQueuedEpisodesStayAfterIt() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.moveToFront("ep-1")
        settingsDataStore.setLastPlayingItem(feedId, "ep-1")

        queueRepository.addToFront("ep-2")
        queueRepository.addToFront("ep-3")

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-3", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun addToFront_nothingPlaying_stillInsertsAtTrueFront() = runTest {
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
    fun peekFront_returnsFrontOfQueueWithoutRemovingIt() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        val next = queueRepository.peekFront()

        assertEquals("ep-1", next)
        assertEquals(listOf("ep-1", "ep-2"), queueRepository.observeQueue().first().map { it.item.id })
    }

    @Test
    fun peekFront_emptyQueue_returnsNull() = runTest {
        assertNull(queueRepository.peekFront())
    }

    @Test
    fun moveToFront_alreadyQueued_movesExistingEntryToFront() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        queueRepository.moveToFront("ep-2")

        assertEquals(listOf("ep-2", "ep-1"), queueRepository.observeQueue().first().map { it.item.id })
    }

    @Test
    fun moveToFront_notQueued_insertsAtFront() = runTest {
        queueRepository.addToEnd("ep-1")

        queueRepository.moveToFront("ep-2")

        assertEquals(listOf("ep-2", "ep-1"), queueRepository.observeQueue().first().map { it.item.id })
    }

    @Test
    fun moveToEnd_queuedEntry_movesToBackOfQueue() = runTest {
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        queueRepository.moveToEnd("ep-1")

        assertEquals(listOf("ep-2", "ep-1"), queueRepository.observeQueue().first().map { it.item.id })
    }

    @Test
    fun moveToEnd_notQueued_doesNothing() = runTest {
        queueRepository.addToEnd("ep-1")

        queueRepository.moveToEnd("ep-2")

        assertEquals(listOf("ep-1"), queueRepository.observeQueue().first().map { it.item.id })
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

    @Test
    fun addToEnd_podcastEpisode_startsDownload() = runTest {
        // issue #219: adding to Next Up, manually or via auto-queue, is the sole download trigger.
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "podcast-1", feedId = feedId, title = "Podcast Episode", itemGuid = "gp1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
            ),
        )

        queueRepository.addToEnd("podcast-1")

        assertEquals(listOf("podcast-1"), enqueuedDownloadItemIds)
    }

    @Test
    fun addToFront_podcastEpisode_startsDownload() = runTest {
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "podcast-1", feedId = feedId, title = "Podcast Episode", itemGuid = "gp1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
            ),
        )

        queueRepository.addToFront("podcast-1")

        assertEquals(listOf("podcast-1"), enqueuedDownloadItemIds)
    }

    // "Already downloaded"/"non-podcast episode" guards are QueueDownloadTrigger's own contract,
    // not QueueRepository's -- covered by EnclosureDownloadRepositoryTest's
    // ensureDownloaded_alreadyDownloaded_doesNotRestartDownload and
    // ensureDownloaded_nonPodcastEpisode_doesNothing. QueueRepository's own contract is just
    // "call the trigger when downloadOnAddToNextUp is on", verified above and below.

    @Test
    fun moveToFront_doesNotStartDownload() = runTest {
        // moveToFront marks "now playing" -- it shouldn't itself trigger a download.
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "podcast-1", feedId = feedId, title = "Podcast Episode", itemGuid = "gp1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
            ),
        )

        queueRepository.moveToFront("podcast-1")

        assertTrue(enqueuedDownloadItemIds.isEmpty())
    }

    @Test
    fun addToEnd_downloadOnAddToNextUpDisabled_doesNotStartDownload() = runTest {
        // issue #219 follow-up: turning this global setting off falls back to episodes only ever
        // downloading via an explicit single-episode download tap.
        settingsDataStore.setDownloadOnAddToNextUp(false)
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "podcast-1", feedId = feedId, title = "Podcast Episode", itemGuid = "gp1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
            ),
        )

        queueRepository.addToEnd("podcast-1")

        assertTrue(enqueuedDownloadItemIds.isEmpty())
        assertTrue(queueRepository.isQueued("podcast-1"))
    }

    @Test
    fun addToFront_downloadOnAddToNextUpDisabled_doesNotStartDownload() = runTest {
        settingsDataStore.setDownloadOnAddToNextUp(false)
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "podcast-1", feedId = feedId, title = "Podcast Episode", itemGuid = "gp1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
            ),
        )

        queueRepository.addToFront("podcast-1")

        assertTrue(enqueuedDownloadItemIds.isEmpty())
        assertTrue(queueRepository.isQueued("podcast-1"))
    }
}
