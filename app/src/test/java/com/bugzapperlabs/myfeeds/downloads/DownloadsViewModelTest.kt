package com.bugzapperlabs.myfeeds.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.DownloadScheduling
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents. This file didn't swap Dispatchers.Main before;
    // it now has to (like every other ViewModel test here), because joining a ViewModel's real
    // job requires a dispatcher that actually runs -- the real Main dispatcher is a paused
    // Robolectric looper that nothing here pumps, so the join hung forever without this.
    private val viewModelStore = TrackedViewModelStore()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var viewModel: DownloadsViewModel
    private var feedId: Long = 0

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = SettingsDataStore(dataStore),
        )
        viewModel = DownloadsViewModel(repository, downloadRepository)
        viewModelStore.put("downloads", viewModel)

        feedId = repository.subscribe(Feed(title = "A Podcast"))
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while clearAndJoin waits out in-flight ViewModel coroutines (issues #54/#60).
        runTest(testDispatcher) { viewModelStore.clearAndJoin() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_completedDownload_usesFileSizeOnDisk() = runTest(testDispatcher) {
        val file = tempFolder.newFile("episode.mp3").apply { writeBytes(ByteArray(1024)) }
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedFilePath = file.absolutePath)),
        )

        val state = viewModel.uiState.first { it.episodes.isNotEmpty() }

        val row = state.episodes.single()
        assertFalse(row.isInProgress)
        assertEquals(1024L, row.sizeBytes)
        assertEquals(1024L, state.totalBytes)
    }

    @Test
    fun uiState_inProgressDownload_usesDownloadedBytes() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedBytes = 512L)),
        )

        val state = viewModel.uiState.first { it.episodes.isNotEmpty() }

        val row = state.episodes.single()
        assertTrue(row.isInProgress)
        assertEquals(512L, row.sizeBytes)
    }

    @Test
    fun delete_removesDownloadAndClearsState() = runTest(testDispatcher) {
        val file = tempFolder.newFile("episode.mp3").apply { writeBytes(ByteArray(1024)) }
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedFilePath = file.absolutePath)),
        )
        val item = viewModel.uiState.first { it.episodes.isNotEmpty() }.episodes.single().item

        viewModel.delete(item)

        assertTrue(repository.observeDownloadedItems().first { it.isEmpty() }.isEmpty())
        assertFalse(file.exists())
    }
}
