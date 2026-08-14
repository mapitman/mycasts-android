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
        queueRepository = QueueRepository(db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
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
}
