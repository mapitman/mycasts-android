package com.bugzapperlabs.myfeeds.feedlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.DownloadScheduling
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import com.bugzapperlabs.myfeeds.refresh.FeedRefreshState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    // This file previously never cleared its ViewModels at all -- their leaked viewModelScope
    // coroutines could dispatch onto whatever Dispatchers.Main a *later* test class had
    // installed. Cleared *and joined* in tearDown; see TrackedViewModelStore's doc for the full
    // leak mechanics behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var viewModel: FeedListViewModel

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)

        queueRepository = QueueRepository(db.queueDao())
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = settingsDataStore,
        )
        viewModel = FeedListViewModel(
            feedRepository = repository,
            feedUpdateEngine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore),
            autoQueueAndDownloadEnforcer = AutoQueueAndDownloadEnforcer(repository, downloadRepository, queueRepository),
            feedRefreshState = FeedRefreshState(),
            settingsDataStore = settingsDataStore,
            context = context,
        )
        viewModelStore.put("feedList", viewModel)
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
    fun uiState_splitsPodcastAndOtherFeedsIntoFixedSections() = runTest(testDispatcher) {
        val podcastFeedId = repository.subscribe(Feed(title = "Podcast Feed"))
        val articleFeedId = repository.subscribe(Feed(title = "Article Feed"))
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "ep-1",
                    feedId = podcastFeedId,
                    itemGuid = "g1",
                    enclosureUrl = "https://example.com/ep1.mp3",
                    enclosureType = "audio/mpeg",
                ),
                FeedItem(id = "art-1", feedId = articleFeedId, itemGuid = "g2"),
            ),
        )

        val state = viewModel.uiState.first { it.sections.any { section -> section.feeds.isNotEmpty() } }

        val podcastsSection = state.sections.first { it.section == FeedListSection.PODCASTS }
        assertEquals(listOf(podcastFeedId), podcastsSection.feeds.map { it.feed.id })

        val feedsSection = state.sections.first { it.section == FeedListSection.FEEDS }
        assertEquals(listOf(articleFeedId), feedsSection.feeds.map { it.feed.id })
    }

    @Test
    fun refresh_autoQueueEnabledFeed_queuesNewEpisode() = runTest(testDispatcher) {
        // issue #88: manual pull-to-refresh should trigger auto-queue, not just the background worker.
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            val feedId = repository.subscribe(Feed(title = "A Podcast", feedUrl = url, autoQueueEnabled = true))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel>
                      <title>A Podcast</title>
                      <link>https://example.com</link>
                      <description>desc</description>
                      <item>
                        <title>Episode 1</title>
                        <link>https://example.com/1</link>
                        <guid>guid-1</guid>
                        <description>Body</description>
                        <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                        <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="1" />
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )

            viewModel.refresh()

            val queue = queueRepository.observeQueue().first { it.isNotEmpty() }
            assertEquals(feedId, queue.single().item.feedId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun uiState_freezesUnreadCountWhileRefreshing() = runTest(testDispatcher) {
        // issue #152: a refresh inserts/evicts items one feed at a time, so reacting to every
        // intermediate DB write made the displayed unread count visibly rise then fall mid-refresh
        // instead of settling once, atomically, when the refresh is actually done.
        val server = MockWebServer()
        server.start()
        try {
            // Keeps the WhileSubscribed(5_000) uiState StateFlow actively collecting for the
            // whole test -- otherwise it goes idle the moment the `first{}` below detaches, and
            // `.value` reads below would return a stale cached emission instead of a live one.
            val collectJob = launch { viewModel.uiState.collect {} }

            val feedId = repository.subscribe(Feed(title = "A Feed", feedUrl = server.url("/feed.xml").toString()))
            repository.insertItems(listOf(FeedItem(id = "existing-1", feedId = feedId, itemGuid = "g-existing")))
            // Waits specifically for the count to settle, not just for the feed to appear --
            // `observeAllFeeds()` and `observeUnreadCountsByFeed()` are separate Flows that can
            // emit an intermediate combination (feed present, count not yet updated) before both
            // settle together.
            val baseline = viewModel.uiState.first { it.totalUnread > 0 }
            assertEquals(1, baseline.totalUnread)

            // A real (small) network delay so the refresh coroutine is genuinely still in-flight
            // (suspended on FeedFetcher's withContext(Dispatchers.IO) network call, a real
            // dispatcher switch, not virtual test time) when this test writes to the DB below.
            server.enqueue(
                MockResponse().setResponseCode(200).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel><title>A Feed</title><link>https://example.com</link>
                    <description>desc</description></channel></rss>
                    """.trimIndent(),
                ),
            )

            val refreshJob = launch { viewModel.refresh() }
            advanceUntilIdle()

            // Simulate a DB write landing mid-refresh (e.g. another feed's own refresh completing
            // sooner) -- the displayed count must not react to it while still refreshing.
            repository.insertItems(listOf(FeedItem(id = "sneaky-new", feedId = feedId, itemGuid = "g-sneaky")))
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.totalUnread)

            refreshJob.join()
            val settled = viewModel.uiState.first { !it.isRefreshing && it.totalUnread == 2 }
            assertEquals(2, settled.totalUnread)
            collectJob.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun uiState_allReadFeedWithNoNewItems_unreadCountNeverRisesDuringRefresh() = runTest(testDispatcher) {
        // issue #152's exact reported scenario, not just a generic race: a feed that's already
        // fully read, refreshed with a response containing no new items, should show 0 unread the
        // entire time -- never a transient nonzero blip while the refresh is in flight.
        val server = MockWebServer()
        server.start()
        try {
            val collectJob = launch { viewModel.uiState.collect {} }

            val feedId = repository.subscribe(Feed(title = "A Feed", feedUrl = server.url("/feed.xml").toString()))
            repository.insertItems(listOf(FeedItem(id = "existing-1", feedId = feedId, itemGuid = "g-existing", isRead = true)))
            val baseline = viewModel.uiState.first { it.sections.any { s -> s.feeds.isNotEmpty() } }
            assertEquals(0, baseline.totalUnread)

            // Same single item, unchanged -- a real refresh with genuinely nothing new.
            server.enqueue(
                MockResponse().setResponseCode(200).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel><title>A Feed</title><link>https://example.com</link>
                    <description>desc</description>
                    <item><title>Existing</title><link>https://example.com/existing</link><guid>g-existing</guid>
                    <description>Body</description><pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate></item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )

            val refreshJob = launch { viewModel.refresh() }
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.totalUnread)

            refreshJob.join()
            val settled = viewModel.uiState.first { !it.isRefreshing }
            assertEquals(0, settled.totalUnread)
            collectJob.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun uiState_bothSectionsPresentEvenWithNoPodcastFeeds() = runTest(testDispatcher) {
        repository.subscribe(Feed(title = "Article Feed"))

        val state = viewModel.uiState.first { it.sections.isNotEmpty() }

        assertEquals(setOf(FeedListSection.PODCASTS, FeedListSection.FEEDS), state.sections.map { it.section }.toSet())
        val podcastsSection = state.sections.first { it.section == FeedListSection.PODCASTS }
        assertTrue(podcastsSection.feeds.isEmpty())
    }
}
