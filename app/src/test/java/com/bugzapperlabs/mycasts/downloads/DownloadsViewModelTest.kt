package com.bugzapperlabs.mycasts.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.TrackedViewModelStore
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import com.bugzapperlabs.mycasts.download.DownloadWorkStatus
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
    private var cancelAllCallCount = 0

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
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {
                    cancelAllCallCount++
                }
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = SettingsDataStore(dataStore),
        )
        viewModel = DownloadsViewModel(repository, downloadRepository)
        viewModelStore.put("downloads", viewModel)

        feedId = repository.subscribe(Feed(title = "A Podcast", imageUrl = "https://example.com/art.png"))
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
        assertEquals("https://example.com/art.png", row.feedImageUrl)
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

    @Test
    fun cancelAllDownloads_delegatesToScheduler() = runTest(testDispatcher) {
        viewModel.cancelAllDownloads()

        assertEquals(1, cancelAllCallCount)
    }

    @Test
    fun activeDownloads_excludesJobAlreadyVisibleInEpisodesList() = runTest(testDispatcher) {
        // issue #173 follow-up: a job that's already written bytes shows up in uiState.episodes
        // too -- without this exclusion it duplicated there until the job actually finished.
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedBytes = 512L)),
        )
        val workInfoFlow = MutableStateFlow(listOf(DownloadWorkInfo("ep-1", DownloadWorkStatus.RUNNING)))
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = workInfoFlow
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = SettingsDataStore(dataStore),
        )
        val dedupeViewModel = DownloadsViewModel(repository, downloadRepository)
        viewModelStore.put("downloads-dedupe", dedupeViewModel)

        dedupeViewModel.uiState.first { it.episodes.isNotEmpty() }
        val active = dedupeViewModel.activeDownloads.first()

        assertTrue("active downloads should not duplicate an already-visible episode", active.isEmpty())
    }

    @Test
    fun activeDownloads_stillShowsJobNotYetVisibleInEpisodesList() = runTest(testDispatcher) {
        // issue #156: a job stuck retrying before writing any bytes is invisible to
        // observeDownloadedItems -- the dedupe above must not hide it too.
        val workInfoFlow = MutableStateFlow(listOf(DownloadWorkInfo("ep-invisible", DownloadWorkStatus.RETRYING)))
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = workInfoFlow
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = SettingsDataStore(dataStore),
        )
        val dedupeViewModel = DownloadsViewModel(repository, downloadRepository)
        viewModelStore.put("downloads-invisible", dedupeViewModel)

        val active = dedupeViewModel.activeDownloads.first { it.isNotEmpty() }

        assertEquals(listOf("ep-invisible"), active.map { it.itemId })
    }

    @Test
    fun toggleSelection_addsAndRemovesFromSelection() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedBytes = 10L),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "g2", downloadedBytes = 10L),
            ),
        )
        viewModel.uiState.first { it.episodes.size == 2 }

        viewModel.toggleSelection("ep-1")
        val selected = viewModel.uiState.first { it.selectedIds.isNotEmpty() }
        assertEquals(setOf("ep-1"), selected.selectedIds)
        assertTrue(selected.isSelectionMode)

        viewModel.toggleSelection("ep-1")
        val deselected = viewModel.uiState.first { it.selectedIds.isEmpty() }
        assertEquals(emptySet<String>(), deselected.selectedIds)
        assertFalse(deselected.isSelectionMode)
    }

    @Test
    fun selectAll_selectsEveryEpisode() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedBytes = 10L),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "g2", downloadedBytes = 10L),
            ),
        )
        viewModel.uiState.first { it.episodes.size == 2 }

        viewModel.selectAll()

        val state = viewModel.uiState.first { it.selectedIds.size == 2 }
        assertEquals(setOf("ep-1", "ep-2"), state.selectedIds)
    }

    @Test
    fun clearSelection_exitsSelectionMode() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedBytes = 10L)),
        )
        viewModel.uiState.first { it.episodes.isNotEmpty() }
        viewModel.toggleSelection("ep-1")
        viewModel.uiState.first { it.selectedIds.isNotEmpty() }

        viewModel.clearSelection()

        val state = viewModel.uiState.first { it.selectedIds.isEmpty() }
        assertFalse(state.isSelectionMode)
    }

    @Test
    fun deleteSelected_deletesOnlySelectedEpisodesAndExitsSelectionMode() = runTest(testDispatcher) {
        val keptFile = tempFolder.newFile("kept.mp3")
        val deletedFile1 = tempFolder.newFile("deleted1.mp3")
        val deletedFile2 = tempFolder.newFile("deleted2.mp3")
        repository.insertItems(
            listOf(
                FeedItem(id = "ep-kept", feedId = feedId, title = "Kept", itemGuid = "g1", downloadedFilePath = keptFile.absolutePath),
                FeedItem(id = "ep-deleted-1", feedId = feedId, title = "Deleted 1", itemGuid = "g2", downloadedFilePath = deletedFile1.absolutePath),
                FeedItem(id = "ep-deleted-2", feedId = feedId, title = "Deleted 2", itemGuid = "g3", downloadedFilePath = deletedFile2.absolutePath),
            ),
        )
        viewModel.uiState.first { it.episodes.size == 3 }
        viewModel.toggleSelection("ep-deleted-1")
        viewModel.toggleSelection("ep-deleted-2")

        viewModel.deleteSelected()

        val remaining = viewModel.uiState.first { it.episodes.size == 1 }
        assertEquals(listOf("ep-kept"), remaining.episodes.map { it.item.id })
        assertFalse(remaining.isSelectionMode)
        assertTrue(keptFile.exists())
        assertFalse(deletedFile1.exists())
        assertFalse(deletedFile2.exists())
    }

    @Test
    fun uiState_selectedEpisodeDeletedElsewhere_dropsItFromSelection() = runTest(testDispatcher) {
        // issue #124's pattern: a stale id for an episode removed some other way (a single-item
        // delete) shouldn't keep inflating the selected count for a row no longer even shown.
        val file = tempFolder.newFile("episode.mp3")
        repository.insertItems(
            listOf(FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", downloadedFilePath = file.absolutePath)),
        )
        viewModel.uiState.first { it.episodes.isNotEmpty() }
        viewModel.toggleSelection("ep-1")
        viewModel.uiState.first { it.selectedIds.isNotEmpty() }

        viewModel.delete(repository.getItem("ep-1")!!)

        val state = viewModel.uiState.first { it.episodes.isEmpty() }
        assertEquals(emptySet<String>(), state.selectedIds)
    }
}
