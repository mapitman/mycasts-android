package com.bugzapperlabs.myfeeds.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.DownloadScheduling
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import com.bugzapperlabs.myfeeds.playback.ChaptersFetcher
import com.bugzapperlabs.myfeeds.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
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

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * The test dispatcher is shared between setMain and runTest, and ViewModels are routed through
 * a TrackedViewModelStore that's cleared *and joined* in tearDown before resetMain -- see that
 * class's doc for the full leak mechanics behind the #54/#60 flakiness this prevents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val viewModelStore = TrackedViewModelStore()
    private var nextViewModelKey = 0

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var playbackController: PlaybackController
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var appContext: android.content.Context
    private var feedId: Long = 0

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        appContext = context
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        playbackController = PlaybackController(
            context,
            settingsDataStore,
            repository,
            queueRepository,
            ChaptersFetcher(OkHttpClient()),
        )
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = settingsDataStore,
        )

        feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "item-1", feedId = feedId, title = "First", itemGuid = "g1", publishDate = 3L),
                FeedItem(id = "item-2", feedId = feedId, title = "Second", itemGuid = "g2", publishDate = 2L),
                FeedItem(id = "item-3", feedId = feedId, title = "Third", itemGuid = "g3", publishDate = 1L),
            ),
        )
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while the joins wait out in-flight coroutines (issues #54/#60). The
        // PlaybackController's own Main-bound scope has to be drained too -- it isn't a
        // ViewModel, so the store's clear doesn't cover it.
        runTest(testDispatcher) {
            viewModelStore.clearAndJoin()
            playbackController.awaitShutdownForTest()
        }
        db.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(itemId: String) =
        ReaderViewModel(
            savedStateHandle = SavedStateHandle(mapOf("feedId" to feedId, "itemId" to itemId)),
            feedRepository = repository,
            playbackController = playbackController,
            downloadRepository = downloadRepository,
            queueRepository = queueRepository,
            settingsDataStore = settingsDataStore,
            context = appContext,
        ).also { viewModelStore.put("reader-${nextViewModelKey++}", it) }

    @Test
    fun uiState_loadsAllItemsOrderedByPublishDateDescending() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-2")

        val state = viewModel.uiState.first { it.items.isNotEmpty() }

        assertEquals(listOf("item-1", "item-2", "item-3"), state.items.map { it.id })
    }

    @Test
    fun uiState_initialIndexMatchesRequestedItem() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-2")

        val state = viewModel.uiState.first { it.items.isNotEmpty() }

        assertEquals(1, state.initialIndex)
    }

    @Test
    fun uiState_includesFeedTitle() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-1")

        val state = viewModel.uiState.first { it.items.isNotEmpty() }

        assertEquals("A Feed", state.feedTitle)
    }

    @Test
    fun markRead_marksItemReadInRepository() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-1")
        val state = viewModel.uiState.first { it.items.isNotEmpty() }

        viewModel.markRead(state.items.first { it.id == "item-1" })

        val item = repository.observeItems(feedId).first { items -> items.first { it.id == "item-1" }.isRead }
            .first { it.id == "item-1" }
        assertTrue(item.isRead)
    }

    @Test
    fun markRead_podcastEpisode_doesNotMarkRead() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "episode-1",
                    feedId = feedId,
                    title = "Episode",
                    itemGuid = "g-episode",
                    publishDate = 4L,
                    enclosureUrl = "https://example.com/episode.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
        val viewModel = createViewModel("episode-1")
        val state = viewModel.uiState.first { it.items.isNotEmpty() }

        viewModel.markRead(state.items.first { it.id == "episode-1" })

        val item = repository.observeItems(feedId).first().first { it.id == "episode-1" }
        assertTrue(!item.isRead)
    }

    @Test
    fun addToQueue_addsItemAndEmitsFeedback() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-1")

        viewModel.addToQueue("item-1")

        assertEquals(appContext.getString(R.string.queue_feedback_added), viewModel.queueFeedback.first { it != null })
        assertTrue(queueRepository.isQueued("item-1"))
    }

    @Test
    fun queuedItemIds_reflectsAddToQueue() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-1")
        viewModel.uiState.first { it.items.isNotEmpty() }

        viewModel.addToQueue("item-1")

        val queuedIds = viewModel.queuedItemIds.first { "item-1" in it }
        assertTrue("item-1" in queuedIds)
    }

    @Test
    fun removeFromQueue_removesItemFromRepository() = runTest(testDispatcher) {
        val viewModel = createViewModel("item-1")
        viewModel.uiState.first { it.items.isNotEmpty() }
        viewModel.addToQueue("item-1")
        viewModel.queuedItemIds.first { "item-1" in it }

        viewModel.removeFromQueue("item-1")

        // Wait for the reactive queue Flow to reflect the removal before asserting via a direct
        // suspend call -- removeFromQueue's DB delete runs on Room's own executor and may not
        // have committed yet at this point even under an unconfined test dispatcher.
        viewModel.queuedItemIds.first { "item-1" !in it }
        assertTrue(!queueRepository.isQueued("item-1"))
    }
}
