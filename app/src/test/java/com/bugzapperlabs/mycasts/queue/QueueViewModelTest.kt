package com.bugzapperlabs.mycasts.queue

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
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import com.bugzapperlabs.mycasts.playback.ChaptersFetcher
import com.bugzapperlabs.mycasts.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
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
class QueueViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var playbackController: PlaybackController
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var viewModel: QueueViewModel
    private var feedId: Long = 0
    private val enqueuedDownloadItemIds = mutableListOf<String>()

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        playbackController = PlaybackController(
            context,
            SettingsDataStore(dataStore),
            feedRepository,
            queueRepository,
            ChaptersFetcher(OkHttpClient()),
        )
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {
                    enqueuedDownloadItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
            },
            settingsDataStore = SettingsDataStore(dataStore),
        )

        feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1"),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "g2"),
            ),
        )
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        viewModel = QueueViewModel(queueRepository, feedRepository, playbackController, downloadRepository, context)
            .also { viewModelStore.put("queue", it) }
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while the joins wait out in-flight coroutines (issues #54/#60). The
        // PlaybackController's own Main-bound scope (which playNow launches into) has to be
        // drained too -- it isn't a ViewModel, so the store's clear doesn't cover it.
        runTest(testDispatcher) {
            viewModelStore.clearAndJoin()
            playbackController.awaitShutdownForTest()
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun queue_reflectsRepositoryOrder() = runTest(testDispatcher) {
        val state = viewModel.queue.first { it.size == 2 }

        assertEquals(listOf("ep-1", "ep-2"), state.map { it.item.id })
    }

    @Test
    fun reorder_updatesQueueOrder() = runTest(testDispatcher) {
        viewModel.queue.first { it.size == 2 }

        viewModel.reorder(listOf("ep-2", "ep-1"))

        val state = viewModel.queue.first { it.map { episode -> episode.item.id } == listOf("ep-2", "ep-1") }
        assertEquals(listOf("ep-2", "ep-1"), state.map { it.item.id })
    }

    @Test
    fun remove_dropsItemFromQueue() = runTest(testDispatcher) {
        viewModel.queue.first { it.size == 2 }

        viewModel.remove("ep-1")

        val state = viewModel.queue.first { it.size == 1 }
        assertEquals(listOf("ep-2"), state.map { it.item.id })
    }

    @Test
    fun remove_stashesEpisodeForUndo() = runTest(testDispatcher) {
        viewModel.queue.first { it.size == 2 }

        viewModel.remove("ep-1")

        val removed = viewModel.removedEpisode.first { it != null }
        assertEquals("ep-1", removed?.episode?.item?.id)
    }

    @Test
    fun undoRemove_restoresEpisodeToItsFormerPosition() = runTest(testDispatcher) {
        viewModel.queue.first { it.size == 2 }
        viewModel.remove("ep-1")
        val removed = viewModel.removedEpisode.first { it != null }!!
        viewModel.queue.first { it.size == 1 }

        viewModel.undoRemove(removed)

        val state = viewModel.queue.first { it.size == 2 }
        assertEquals(listOf("ep-1", "ep-2"), state.map { it.item.id })
    }

    @Test
    fun sortByPublishDate_togglesBetweenOldestFirstAndNewestFirst() = runTest(testDispatcher) {
        // ep-1/ep-2 (from setUp) have no publish date -- exercises null handling too: nulls sort
        // first ascending, last descending, per Kotlin's default null-ordering semantics.
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-newest", feedId = feedId, title = "Newest", itemGuid = "g-newest", publishDate = 300L),
                FeedItem(id = "ep-oldest", feedId = feedId, title = "Oldest", itemGuid = "g-oldest", publishDate = 100L),
                FeedItem(id = "ep-middle", feedId = feedId, title = "Middle", itemGuid = "g-middle", publishDate = 200L),
            ),
        )
        queueRepository.addToEnd("ep-newest")
        queueRepository.addToEnd("ep-oldest")
        queueRepository.addToEnd("ep-middle")
        viewModel.queue.first { it.size == 5 }
        assertTrue(viewModel.sortAscending.first())

        viewModel.sortByPublishDate()

        val ascending = viewModel.queue.first { it.map { e -> e.item.id } == listOf("ep-1", "ep-2", "ep-oldest", "ep-middle", "ep-newest") }
        assertEquals(listOf("ep-1", "ep-2", "ep-oldest", "ep-middle", "ep-newest"), ascending.map { it.item.id })
        assertFalse(viewModel.sortAscending.first())

        viewModel.sortByPublishDate()

        val descending = viewModel.queue.first { it.map { e -> e.item.id } == listOf("ep-newest", "ep-middle", "ep-oldest", "ep-1", "ep-2") }
        assertEquals(listOf("ep-newest", "ep-middle", "ep-oldest", "ep-1", "ep-2"), descending.map { it.item.id })
        assertTrue(viewModel.sortAscending.first())
    }

    @Test
    fun playNow_movesEpisodeToFrontOfQueue() = runTest(testDispatcher) {
        // issue #196: the currently-playing episode stays queued (as the front entry, clearly
        // marked as playing) rather than being dequeued -- playing the queue's second episode
        // should swap the two, not shrink the queue.
        val episode = viewModel.queue.first { it.size == 2 }.last()

        viewModel.playNow(episode)

        val state = viewModel.queue.first { it.first().item.id == "ep-2" }
        assertEquals(listOf("ep-2", "ep-1"), state.map { it.item.id })
    }

    @Test
    fun downloadAll_startsDownloadForEveryEligibleQueuedEpisode() = runTest(testDispatcher) {
        // issue #188: ep-1/ep-2 (from setUp) aren't podcast episodes (no enclosure), so only these
        // two newly-added, genuinely downloadable episodes should be started.
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = "ep-downloadable-1", feedId = feedId, title = "Downloadable 1", itemGuid = "g-dl-1",
                    enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg",
                ),
                FeedItem(
                    id = "ep-downloadable-2", feedId = feedId, title = "Downloadable 2", itemGuid = "g-dl-2",
                    enclosureUrl = "https://example.com/2.mp3", enclosureType = "audio/mpeg",
                ),
                FeedItem(
                    id = "ep-already-downloaded", feedId = feedId, title = "Already Downloaded", itemGuid = "g-dl-3",
                    enclosureUrl = "https://example.com/3.mp3", enclosureType = "audio/mpeg",
                    downloadedFilePath = "/tmp/already.mp3",
                ),
            ),
        )
        queueRepository.addToEnd("ep-downloadable-1")
        queueRepository.addToEnd("ep-downloadable-2")
        queueRepository.addToEnd("ep-already-downloaded")
        viewModel.queue.first { it.size == 5 }

        viewModel.downloadAll()

        // Waiting on downloadFeedback first guarantees downloadAll's launch has actually run to
        // completion -- it's the last thing that coroutine sets -- before enqueuedDownloadItemIds
        // is checked below.
        val feedback = viewModel.downloadFeedback.first { it != null }
        assertEquals("2 downloads started", feedback)
        assertEquals(setOf("ep-downloadable-1", "ep-downloadable-2"), enqueuedDownloadItemIds.toSet())
    }

    @Test
    fun downloadAll_noEligibleEpisodes_reportsAlreadyDownloaded() = runTest(testDispatcher) {
        // ep-1/ep-2 aren't podcast episodes, so nothing in the queue is eligible.
        viewModel.queue.first { it.size == 2 }

        viewModel.downloadAll()

        assertTrue(enqueuedDownloadItemIds.isEmpty())
        val feedback = viewModel.downloadFeedback.first { it != null }
        assertEquals("Already downloaded", feedback)
    }
}
