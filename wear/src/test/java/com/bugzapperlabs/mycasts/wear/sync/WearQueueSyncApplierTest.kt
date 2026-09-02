package com.bugzapperlabs.mycasts.wear.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueDownloadTrigger
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.sync.SyncQueueItem
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class WearQueueSyncApplierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var applier: WearQueueSyncApplier

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val noopTrigger = object : QueueDownloadTrigger {
            override suspend fun ensureDownloaded(item: FeedItem) {}
        }
        queueRepository = QueueRepository(db.queueDao(), feedRepository, noopTrigger, SettingsDataStore(dataStore))
        applier = WearQueueSyncApplier(feedRepository, queueRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun syncItem(itemId: String, orderIndex: Int, feedId: Long = 1L) = SyncQueueItem(
        itemId = itemId, feedId = feedId, title = "Episode $itemId", feedTitle = "A Feed",
        enclosureUrl = "https://example.com/$itemId.mp3", artworkUrl = null,
        durationMs = 60_000L, positionMs = 1_000L, orderIndex = orderIndex,
    )

    @Test
    fun apply_createsFeedAndItemsAndQueueInOrder() = runTest {
        applier.apply(listOf(syncItem("ep-1", 0), syncItem("ep-2", 1)))

        assertEquals("A Feed", feedRepository.getFeed(1L)?.title)
        assertEquals("https://example.com/ep-1.mp3", feedRepository.getItem("ep-1")?.enclosureUrl)
        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }

    @Test
    fun apply_secondSnapshot_removesStaleEntries() = runTest {
        applier.apply(listOf(syncItem("ep-1", 0), syncItem("ep-2", 1)))

        // ep-1 was removed from the phone's queue since the last sync.
        applier.apply(listOf(syncItem("ep-2", 0)))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-2"), queue.map { it.item.id })
    }

    @Test
    fun apply_repeatSyncOfSameFeed_doesNotThrow() = runTest {
        applier.apply(listOf(syncItem("ep-1", 0)))

        applier.apply(listOf(syncItem("ep-1", 0), syncItem("ep-2", 1)))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }
}
