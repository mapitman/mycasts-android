package com.bugzapperlabs.mycasts.feedlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.TrackedViewModelStore
import com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.FeedRefreshLocks
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator
import com.bugzapperlabs.mycasts.data.opml.OpmlImporter
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import com.bugzapperlabs.mycasts.refresh.FeedRefreshState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
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
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var context: android.content.Context
    private lateinit var viewModel: FeedListViewModel

    // OpmlImportCoordinator runs on its own real (non-viewModelScope) scope, so every one created
    // by newViewModel(WithCoordinator) below needs cancelForTest() explicitly in tearDown --
    // viewModelStore's own clearAndJoin() only reaches viewModelScope coroutines, not this
    // separate one.
    private val coordinators = mutableListOf<OpmlImportCoordinator>()

    private fun newViewModel(
        feedRefreshState: FeedRefreshState,
        feedFetcher: FeedFetcher = FeedFetcher(OkHttpClient()),
    ): FeedListViewModel = newViewModelWithCoordinator(feedRefreshState, feedFetcher).first

    private fun newViewModelWithCoordinator(
        feedRefreshState: FeedRefreshState,
        feedFetcher: FeedFetcher = FeedFetcher(OkHttpClient()),
    ): Pair<FeedListViewModel, OpmlImportCoordinator> {
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore, FeedRefreshLocks())
        val enforcer = AutoQueueAndDownloadEnforcer(repository, queueRepository)
        val coordinator = OpmlImportCoordinator(
            OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore, enforcer),
            context,
        )
        coordinators += coordinator
        val viewModel = FeedListViewModel(
            feedRepository = repository,
            feedUpdateEngine = feedUpdateEngine,
            autoQueueAndDownloadEnforcer = enforcer,
            feedRefreshState = feedRefreshState,
            opmlImportCoordinator = coordinator,
            settingsDataStore = settingsDataStore,
            context = context,
        )
        return viewModel to coordinator
    }

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        queueRepository = QueueRepository(db.queueDao(), repository, downloadRepository, settingsDataStore)

        viewModel = newViewModel(FeedRefreshState())
        viewModelStore.put("feedList", viewModel)
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while clearAndJoin waits out in-flight ViewModel coroutines (issues #54/#60).
        runTest(testDispatcher) {
            viewModelStore.clearAndJoin()
            coordinators.forEach { it.cancelForTest() }
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_listsAllSubscribedFeeds() = runTest(testDispatcher) {
        val firstFeedId = repository.subscribe(Feed(title = "First Feed"))
        val secondFeedId = repository.subscribe(Feed(title = "Second Feed"))
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "ep-1",
                    feedId = firstFeedId,
                    itemGuid = "g1",
                    enclosureUrl = "https://example.com/ep1.mp3",
                    enclosureType = "audio/mpeg",
                ),
                FeedItem(id = "ep-2", feedId = secondFeedId, itemGuid = "g2"),
            ),
        )

        // Waits for both feeds to have landed (issue #261) -- Room delivers each subscribed
        // feed's Flow update from its own background invalidation-tracker thread, so a naive
        // `.isNotEmpty()` check can return on a partial, still-settling state.
        val state = viewModel.uiState.first { it.feeds.size == 2 }

        assertEquals(setOf(firstFeedId, secondFeedId), state.feeds.map { it.feed.id }.toSet())
    }

    @Test
    fun uiState_marksFeedsWithNewEpisodesSinceLastOpen() = runTest(testDispatcher) {
        // issue #161: highlighted directly in the podcast list rather than routed through a
        // separate screen, so it's visible whether or not the user ever taps a notification.
        val highlightedFeedId = repository.subscribe(Feed(title = "Has New Episode"))
        val otherFeedId = repository.subscribe(Feed(title = "No New Episode"))
        repository.insertItems(
            listOf(
                FeedItem(id = "new-ep", feedId = highlightedFeedId, itemGuid = "g1"),
                FeedItem(id = "old-ep", feedId = otherFeedId, itemGuid = "g2"),
            ),
        )
        settingsDataStore.addPendingNewEpisodeIds(listOf("new-ep"))
        settingsDataStore.markAppOpened()

        // Waits specifically for the highlight to land, not just for both feeds to appear
        // (issue #161's feedIdsWithNewEpisodes branch is a separate, independently-scheduled
        // child of the same combine() as the feeds themselves, so "feeds.size == 2" alone can
        // settle before this branch has caught up with the settings write above).
        val state = viewModel.uiState.first { it.feeds.size == 2 && it.feeds.any { f -> f.hasNewEpisodes } }

        val highlighted = state.feeds.single { it.feed.id == highlightedFeedId }
        val other = state.feeds.single { it.feed.id == otherFeedId }
        assertTrue(highlighted.hasNewEpisodes)
        assertFalse(other.hasNewEpisodes)
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

            // Blocks the mock response on a latch the test releases explicitly, rather than a
            // real wall-clock delay (issue #261) -- the refresh coroutine still genuinely
            // suspends on FeedFetcher's withContext(Dispatchers.IO) network call (a real
            // dispatcher switch, not virtual test time) when this test writes to the DB below,
            // but the response no longer races a fixed real-time duration against however fast
            // (or slow, under CI load) the request actually reaches that point.
            val responseGate = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    responseGate.await()
                    return MockResponse().setResponseCode(200).setBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0"><channel><title>A Feed</title><link>https://example.com</link>
                        <description>desc</description></channel></rss>
                        """.trimIndent(),
                    )
                }
            }

            val refreshJob = launch { viewModel.refresh() }
            advanceUntilIdle()

            // Simulate a DB write landing mid-refresh (e.g. another feed's own refresh completing
            // sooner) -- the displayed count must not react to it while still refreshing.
            repository.insertItems(listOf(FeedItem(id = "sneaky-new", feedId = feedId, itemGuid = "g-sneaky")))
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.totalUnread)

            responseGate.countDown()
            refreshJob.join()
            val settled = viewModel.uiState.first { !it.isRefreshing && it.totalUnread == 2 }
            assertEquals(2, settled.totalUnread)
            collectJob.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun uiState_showsExistingFeedsImmediatelyEvenIfARefreshIsAlreadyRunningAtLaunch() = runTest(testDispatcher) {
        // issue #276: a scheduled refresh (e.g. FeedRefreshWorker firing right at launch) can
        // already be running by the time this screen's own collector takes its first snapshot.
        // Requiring `!refreshing` unconditionally left stableSource stuck at its empty default for
        // the whole refresh, rendering the feed list as blank instead of showing what's already in
        // the DB. Builds a fresh ViewModel against a FeedRefreshState that's already marked
        // refreshing *before* construction, reproducing the exact race.
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(listOf(FeedItem(id = "existing-1", feedId = feedId, itemGuid = "g-existing")))

        val refreshState = FeedRefreshState()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val trackingJob = launch {
            refreshState.track {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            }
        }
        refreshStarted.await()

        val freshViewModel = newViewModel(refreshState)
        viewModelStore.put("freshFeedList", freshViewModel)
        val collectJob = launch { freshViewModel.uiState.collect {} }

        val state = freshViewModel.uiState.first { it.feeds.isNotEmpty() }
        assertEquals(1, state.feeds.size)
        assertTrue(state.isRefreshing)

        collectJob.cancel()
        releaseRefresh.complete(Unit)
        trackingJob.join()
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
            val baseline = viewModel.uiState.first { it.feeds.isNotEmpty() }
            assertEquals(0, baseline.totalUnread)

            // Same single item, unchanged -- a real refresh with genuinely nothing new. Blocked on
            // a latch the test releases explicitly rather than a real wall-clock delay (issue
            // #261), same as uiState_freezesUnreadCountWhileRefreshing above.
            val responseGate = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    responseGate.await()
                    return MockResponse().setResponseCode(200).setBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0"><channel><title>A Feed</title><link>https://example.com</link>
                        <description>desc</description>
                        <item><title>Existing</title><link>https://example.com/existing</link><guid>g-existing</guid>
                        <description>Body</description><pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate></item>
                        </channel></rss>
                        """.trimIndent(),
                    )
                }
            }

            val refreshJob = launch { viewModel.refresh() }
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.totalUnread)

            responseGate.countDown()
            refreshJob.join()
            val settled = viewModel.uiState.first { !it.isRefreshing }
            assertEquals(0, settled.totalUnread)
            collectJob.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun uiState_listsSubscribedFeedWithNoEpisodesYet() = runTest(testDispatcher) {
        val feedId = repository.subscribe(Feed(title = "A Feed"))

        val state = viewModel.uiState.first { it.feeds.isNotEmpty() }

        assertEquals(listOf(feedId), state.feeds.map { it.feed.id })
    }

    @Test
    fun showAddDefaultFeedsPrompt_trueOnFreshEmptyDatabase() = runTest(testDispatcher) {
        // A bare .first() on a WhileSubscribed StateFlow can return its seed default (false) before
        // the underlying combine has actually run once -- waited on the real true value instead,
        // same reason other tests in this file predicate their `first{}` calls rather than trusting
        // an immediate read.
        assertTrue(viewModel.showAddDefaultFeedsPrompt.first { it })
    }

    @Test
    fun showAddDefaultFeedsPrompt_falseOnceAlreadyShown() = runTest(testDispatcher) {
        settingsDataStore.setAddDefaultFeedsPromptShown(true)

        assertFalse(viewModel.showAddDefaultFeedsPrompt.first { !it })
    }

    @Test
    fun showAddDefaultFeedsPrompt_falseOnceFeedsExist() = runTest(testDispatcher) {
        repository.subscribe(Feed(title = "A Feed"))

        assertFalse(viewModel.showAddDefaultFeedsPrompt.first { !it })
    }

    @Test
    fun dismissAddDefaultFeedsPrompt_marksShownWithoutImportingAnything() = runTest(testDispatcher) {
        viewModel.dismissAddDefaultFeedsPrompt()

        assertTrue(settingsDataStore.settings.first { it.addDefaultFeedsPromptShown }.addDefaultFeedsPromptShown)
        assertFalse(viewModel.showAddDefaultFeedsPrompt.first { !it })
        assertTrue(repository.observeAllFeeds().first().isEmpty())
    }

    @Test
    fun acceptAddDefaultFeedsPrompt_marksShownAndImportsTheBundledFeeds() = runTest(testDispatcher) {
        // default_feeds.opml lists real external hosts (issue #231 validates each by actually
        // fetching it), so every outgoing request is rewritten to this local server regardless of
        // its original host, and answered with a generic valid RSS body -- same rewrite trick as
        // SettingsViewModelTest.addDefaultFeeds_importsBundledOpml.
        val server = MockWebServer()
        server.start()
        try {
            val rssXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>A Feed</title>
                  <link>https://example.com</link>
                  <description>desc</description>
                </channel></rss>
            """.trimIndent()
            repeat(7) { server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml)) }
            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val rewritten = original.url.newBuilder()
                        .scheme(server.url("/").scheme)
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }
                .build()
            val (freshViewModel, coordinator) = newViewModelWithCoordinator(FeedRefreshState(), feedFetcher = FeedFetcher(httpClient))
            viewModelStore.put("acceptPrompt", freshViewModel)

            freshViewModel.acceptAddDefaultFeedsPrompt()

            val feeds = db.feedDao().observeAll().first { it.size == 7 }
            assertEquals(7, feeds.size)
            assertTrue(settingsDataStore.settings.first().addDefaultFeedsPromptShown)
            // Reaching feeds.size == 7 only guarantees the last item write landed, not that the
            // coordinator's own coroutine has finished entirely (it still sets _progress/_result
            // afterward) -- cancelled and joined here, before shutting the server down below, so
            // nothing is left mid-flight against it (same reasoning as cancelForTest's own doc).
            coordinator.cancelForTest()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun toggleSelection_entersSelectionMode() = runTest(testDispatcher) {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        viewModel.uiState.first { it.feeds.isNotEmpty() }

        viewModel.toggleSelection(feedId)

        val selected = viewModel.uiState.first { it.isSelectionMode }
        assertEquals(setOf(feedId), selected.selectedIds)
    }

    @Test
    fun toggleSelection_deselectingTheOnlySelectedFeedStaysInSelectionMode() = runTest(testDispatcher) {
        // Selection mode only ends via clearSelection() (the top bar's close icon), not merely by
        // the selection becoming empty -- otherwise unchecking the last row would unexpectedly
        // kick the user back to the normal top bar/FAB mid-selection.
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        viewModel.uiState.first { it.feeds.isNotEmpty() }
        viewModel.toggleSelection(feedId)
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.toggleSelection(feedId)

        val deselected = viewModel.uiState.first { it.selectedIds.isEmpty() }
        assertTrue(deselected.isSelectionMode)
    }

    @Test
    fun selectAll_selectsEveryCurrentFeed() = runTest(testDispatcher) {
        val firstFeedId = repository.subscribe(Feed(title = "First Feed"))
        val secondFeedId = repository.subscribe(Feed(title = "Second Feed"))
        viewModel.uiState.first { it.feeds.size == 2 }

        viewModel.selectAll()

        val selected = viewModel.uiState.first { it.selectedIds.size == 2 }
        assertEquals(setOf(firstFeedId, secondFeedId), selected.selectedIds)
    }

    @Test
    fun selectAll_calledAgainWithEveryFeedAlreadySelected_deselectsAllButStaysInSelectionMode() = runTest(testDispatcher) {
        repository.subscribe(Feed(title = "First Feed"))
        repository.subscribe(Feed(title = "Second Feed"))
        viewModel.uiState.first { it.feeds.size == 2 }
        viewModel.selectAll()
        viewModel.uiState.first { it.selectedIds.size == 2 }

        viewModel.selectAll()

        val deselected = viewModel.uiState.first { it.selectedIds.isEmpty() }
        assertTrue(deselected.isSelectionMode)
    }

    @Test
    fun clearSelection_exitsSelectionMode() = runTest(testDispatcher) {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        viewModel.uiState.first { it.feeds.isNotEmpty() }
        viewModel.toggleSelection(feedId)
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.clearSelection()

        viewModel.uiState.first { !it.isSelectionMode }
    }

    @Test
    fun markSelectedRead_marksOnlySelectedFeedsAndExitsSelectionMode() = runTest(testDispatcher) {
        val readFeedId = repository.subscribe(Feed(title = "To Mark Read"))
        val untouchedFeedId = repository.subscribe(Feed(title = "Untouched"))
        repository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = readFeedId, itemGuid = "g1"),
                FeedItem(id = "ep-2", feedId = untouchedFeedId, itemGuid = "g2"),
            ),
        )
        viewModel.uiState.first { it.feeds.size == 2 }
        viewModel.toggleSelection(readFeedId)
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.markSelectedRead()
        viewModel.uiState.first { !it.isSelectionMode }

        val items = repository.observeItems(readFeedId).first { items -> items.all { it.isRead } }
        assertTrue(items.first { it.id == "ep-1" }.isRead)
        assertFalse(repository.observeItems(untouchedFeedId).first().first { it.id == "ep-2" }.isRead)
    }

    @Test
    fun deleteSelected_unsubscribesOnlySelectedFeedsAndExitsSelectionMode() = runTest(testDispatcher) {
        val toDeleteId = repository.subscribe(Feed(title = "To Delete"))
        val keptId = repository.subscribe(Feed(title = "Kept"))
        viewModel.uiState.first { it.feeds.size == 2 }
        viewModel.toggleSelection(toDeleteId)
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.deleteSelected()
        advanceUntilIdle()

        val remaining = viewModel.uiState.first { it.feeds.size == 1 }
        assertEquals(listOf(keptId), remaining.feeds.map { it.feed.id })
        assertFalse(remaining.isSelectionMode)
    }
}
