package com.bugzapperlabs.mycasts.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class AppBackupRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var backupRepository: AppBackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        queueRepository = QueueRepository(db.queueDao(), feedRepository, downloadRepository)
        backupRepository = AppBackupRepository(feedRepository, queueRepository, settingsDataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun export_import_roundTripsFeedsItemsQueueAndSettings() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", startSkipSeconds = 30))
        feedRepository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, itemGuid = "g1", title = "Episode 1", isRead = true)),
        )
        queueRepository.addToEnd("ep-1")
        settingsDataStore.setFontSize(1.5f)

        val json = backupRepository.export()

        // Simulates restoring onto a device with different existing data (issue #157) -- the
        // restore should replace it wholesale, not merge with it.
        val otherFeedId = feedRepository.subscribe(Feed(title = "Some Other Podcast"))
        feedRepository.insertItems(listOf(FeedItem(id = "other-ep", feedId = otherFeedId, itemGuid = "g2")))
        settingsDataStore.setFontSize(2.0f)

        backupRepository.import(json)

        val feeds = feedRepository.observeAllFeeds().first()
        assertEquals(1, feeds.size)
        assertEquals("A Podcast", feeds.single().title)
        assertEquals(30, feeds.single().startSkipSeconds)

        val restoredItem = feedRepository.getItem("ep-1")
        assertEquals("Episode 1", restoredItem?.title)
        assertTrue(restoredItem?.isRead == true)

        val queue = queueRepository.observeQueuedItemIds().first()
        assertEquals(setOf("ep-1"), queue)

        assertEquals(1.5f, settingsDataStore.settings.first().fontSize)
    }

    /**
     * issue #197: the currently-playing episode needs no special handling of its own here --
     * since issue #196, it's a real `queue_entries` row like any other (always the front one),
     * and [FeedItem.enclosurePosition]/`lastPlayingFeedId`/`lastPlayingItemId` were already backed
     * up. All three already round-trip through the existing export/import path, so restoring a
     * backup resumes right where playback left off, not just with the episode back in Next Up.
     */
    @Test
    fun export_import_roundTripsCurrentlyPlayingEpisode() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast"))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "now-playing", feedId = feedId, itemGuid = "g1", title = "Now Playing", enclosurePosition = 42.5),
                FeedItem(id = "up-next", feedId = feedId, itemGuid = "g2", title = "Up Next"),
            ),
        )
        queueRepository.addToEnd("up-next")
        queueRepository.moveToFront("now-playing")
        settingsDataStore.setLastPlayingItem(feedId, "now-playing")

        val json = backupRepository.export()

        val otherFeedId = feedRepository.subscribe(Feed(title = "Some Other Podcast"))
        feedRepository.insertItems(listOf(FeedItem(id = "other-ep", feedId = otherFeedId, itemGuid = "g3")))
        settingsDataStore.setLastPlayingItem(otherFeedId, "other-ep")

        backupRepository.import(json)

        assertEquals(listOf("now-playing", "up-next"), queueRepository.observeQueue().first().map { it.item.id })
        assertEquals(42.5, feedRepository.getItem("now-playing")?.enclosurePosition)
        val settings = settingsDataStore.settings.first()
        assertEquals(feedId, settings.lastPlayingFeedId)
        assertEquals("now-playing", settings.lastPlayingItemId)
    }
}
