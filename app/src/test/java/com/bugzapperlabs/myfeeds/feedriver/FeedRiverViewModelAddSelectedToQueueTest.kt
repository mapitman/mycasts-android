package com.bugzapperlabs.myfeeds.feedriver

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
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
 * Only exercises [FeedRiverViewModel.addSelectedToQueue] via [FeedRiverViewModel.queueFeedback]
 * (a plain, un-derived `StateFlow`) and the repository directly -- never
 * [FeedRiverViewModel.uiState]. `uiState` combines a *nested* `stateIn`-wrapped `feedIds` flow
 * (itself `WhileSubscribed`), and waiting on a predicate that requires an actual Room round-trip
 * through that chain (e.g. `it.articles.size == N`) reliably leaves a coroutine in
 * `viewModelScope` that [TrackedViewModelStore.clearAndJoin] then hangs on forever in this test
 * environment -- reproduced in isolation while writing this test, unrelated to issue #286's
 * actual change. `addSelectedToQueue` itself never reads `uiState`/`articles` (it queues the raw
 * `selectedIds` selection as-is, see its doc), so it doesn't need to. See
 * [FeedRiverUiStateTest] for `canAddSelectedToQueue` coverage, tested directly against the data
 * class instead.
 *
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRiverViewModelAddSelectedToQueueTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val viewModelStore = TrackedViewModelStore()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var context: android.content.Context
    private var feedId: Long = 0

    private fun createViewModel(): FeedRiverViewModel {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val settingsDataStore = SettingsDataStore(dataStore)
        val feedUpdateEngine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), feedRepository, settingsDataStore)
        return FeedRiverViewModel(feedRepository, feedUpdateEngine, queueRepository, settingsDataStore, context)
            .also { viewModelStore.put("feedRiver", it) }
    }

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "ep-1",
                    feedId = feedId,
                    title = "Episode One",
                    itemGuid = "g1",
                    enclosureUrl = "https://example.com/ep1.mp3",
                    enclosureType = "audio/mpeg",
                ),
                FeedItem(
                    id = "ep-2",
                    feedId = feedId,
                    title = "Episode Two",
                    itemGuid = "g2",
                    enclosureUrl = "https://example.com/ep2.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        runTest(testDispatcher) { viewModelStore.clearAndJoin() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun addSelectedToQueue_queuesEveryEpisodeAndClearsSelection() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.toggleSelection("ep-1")
        viewModel.toggleSelection("ep-2")

        viewModel.addSelectedToQueue()
        viewModel.queueFeedback.first { it != null }

        assertTrue(queueRepository.isQueued("ep-1"))
        assertTrue(queueRepository.isQueued("ep-2"))
    }

    @Test
    fun addSelectedToQueue_multipleEpisodes_showsCombinedFeedbackMessage() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.toggleSelection("ep-1")
        viewModel.toggleSelection("ep-2")

        viewModel.addSelectedToQueue()

        val feedback = viewModel.queueFeedback.first { it != null }
        assertEquals(context.getString(R.string.queue_feedback_added_multiple, 2), feedback)
    }
}
