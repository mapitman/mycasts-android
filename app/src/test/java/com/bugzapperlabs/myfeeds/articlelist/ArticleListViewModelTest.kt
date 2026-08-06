package com.bugzapperlabs.myfeeds.articlelist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Formerly skipped in CI (issues #54/#60/#215): `addSelectedToQueue_queuesOnlyPodcastEpisodesAndClearsSelection`
 * hung whenever run after another test that called `viewModel.refresh()`. Root-caused to a real
 * race in `ArticleListViewModel`'s `init` block -- its asynchronous settings-derived default for
 * `showUnreadOnly` could silently clobber an explicit `setShowUnreadOnly()` call made before that
 * read resolved, if the read required a genuine suspension rather than completing synchronously.
 * Fixed in `ArticleListViewModel` (a `showUnreadOnlyExplicitlySet` guard), not just this test --
 * see the fix commit for the full diagnosis. Skip removed accordingly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var feedUpdateEngine: FeedUpdateEngine
    private lateinit var autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer
    private lateinit var context: android.content.Context
    private var feedId: Long = 0
    private var nextViewModelKey = 0

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()

    private fun createViewModel(): ArticleListViewModel =
        ArticleListViewModel(
            savedStateHandle = SavedStateHandle(mapOf("feedId" to feedId)),
            feedRepository = repository,
            feedUpdateEngine = feedUpdateEngine,
            autoQueueAndDownloadEnforcer = autoQueueAndDownloadEnforcer,
            queueRepository = queueRepository,
            settingsDataStore = settingsDataStore,
            context = context,
        ).also { viewModelStore.put("articleList-${nextViewModelKey++}", it) }

    @Before
    fun setUp() {
        runTestBody()
    }

    private fun runTestBody() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        feedUpdateEngine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = settingsDataStore,
        )
        autoQueueAndDownloadEnforcer = AutoQueueAndDownloadEnforcer(repository, downloadRepository, queueRepository)

        feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "unread-1", feedId = feedId, title = "Unread One", itemGuid = "g1", isRead = false),
                FeedItem(id = "read-1", feedId = feedId, title = "Read One", itemGuid = "g2", isRead = true),
            ),
        )
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
    fun defaultState_showsUnreadOnlyByDefault() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it.feedTitle == "A Feed" }

        assertTrue(state.showUnreadOnly)
        assertEquals(listOf("unread-1"), state.articles.map { it.id })
        assertEquals(1, state.unreadCount)
    }

    @Test
    fun defaultToAllArticleViewSetting_showsAllByDefault() = runTest(testDispatcher) {
        settingsDataStore.setDefaultToAllArticleView(true)
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it.feedTitle == "A Feed" }

        assertFalse(state.showUnreadOnly)
        assertEquals(2, state.articles.size)
    }

    @Test
    fun setShowUnreadOnly_switchesArticleList() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.uiState.first { it.feedTitle == "A Feed" }

        viewModel.setShowUnreadOnly(false)
        val state = viewModel.uiState.first { !it.showUnreadOnly && it.articles.size == 2 }

        assertEquals(2, state.articles.size)
    }

    @Test
    fun toggleSelection_entersAndExitsSelectionMode() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.uiState.first { it.feedTitle == "A Feed" }

        viewModel.toggleSelection("unread-1")
        val selected = viewModel.uiState.first { it.isSelectionMode }
        assertEquals(setOf("unread-1"), selected.selectedIds)

        viewModel.toggleSelection("unread-1")
        viewModel.uiState.first { !it.isSelectionMode }
    }

    @Test
    fun selectAll_selectsEveryCurrentlyVisibleArticle() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.uiState.first { it.feedTitle == "A Feed" }

        viewModel.selectAll()

        // Default view is unread-only, so only "unread-1" is visible to select.
        val selected = viewModel.uiState.first { it.selectedIds.isNotEmpty() }
        assertEquals(setOf("unread-1"), selected.selectedIds)
    }

    @Test
    fun markSelectedRead_updatesItemsAndClearsSelection() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.uiState.first { it.feedTitle == "A Feed" }
        viewModel.toggleSelection("unread-1")
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.markSelectedRead(true)
        viewModel.uiState.first { !it.isSelectionMode }

        val items = repository.observeItems(feedId).first { items -> items.all { it.isRead } }
        assertTrue(items.first { it.id == "unread-1" }.isRead)
        assertEquals(0, repository.observeUnreadCount(feedId).first())
    }

    @Test
    fun refresh_feedHasNoUrl_setsRefreshErrorAndClearsIsRefreshing() = runTest(testDispatcher) {
        // The test fixture feed has no feedUrl, so FeedUpdateEngine.updateFeed fails deterministically
        // without needing a real network call.
        val viewModel = createViewModel()
        viewModel.uiState.first { it.feedTitle == "A Feed" }

        viewModel.refresh()

        assertNotNull(viewModel.refreshError.first { it != null })
        // Waits for isRefreshing to actually settle rather than reading .value immediately after
        // refreshError resolves (issue #215) -- refresh()'s _refreshError.value = ... and
        // isRefreshing.value = false run on whatever thread completed the feedRepository.getFeed
        // suspension (a real Room dispatch, not virtual test time), so nothing guarantees the
        // second write has landed yet just because a collector observed the first.
        val settled = viewModel.uiState.first { !it.isRefreshing }
        assertFalse(settled.isRefreshing)
    }

    @Test
    fun refresh_autoQueueEnabledFeed_queuesNewEpisode() = runTest(testDispatcher) {
        // issue #88: manual pull-to-refresh should trigger auto-queue, not just the background worker.
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            feedId = repository.subscribe(Feed(title = "A Podcast", feedUrl = url, autoQueueEnabled = true))
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
            val viewModel = createViewModel()
            viewModel.uiState.first { it.feedTitle == "A Podcast" }

            viewModel.refresh()

            val queue = queueRepository.observeQueue().first { it.isNotEmpty() }
            assertEquals(feedId, queue.single().item.feedId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun defaultState_regularFeed_isNotPodcastFeed() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it.feedTitle == "A Feed" }

        assertFalse(state.isPodcastFeed)
    }

    @Test
    fun defaultState_podcastFeed_isPodcastFeed() = runTest(testDispatcher) {
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "episode-1",
                    feedId = feedId,
                    title = "Episode",
                    itemGuid = "g-episode",
                    enclosureUrl = "https://example.com/episode.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
        val viewModel = createViewModel()

        val state = viewModel.uiState.first { it.feedTitle == "A Feed" && it.isPodcastFeed }

        assertTrue(state.isPodcastFeed)
    }

    @Test
    fun addSelectedToQueue_queuesOnlyPodcastEpisodesAndClearsSelection() = runTest(testDispatcher) {
        // issue #159: selection mode isn't podcast-specific, so a plain article ("read-1", no
        // enclosure) mixed into the selection should be silently skipped rather than queued.
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "episode-1",
                    feedId = feedId,
                    title = "Episode One",
                    itemGuid = "g-episode-1",
                    enclosureUrl = "https://example.com/ep1.mp3",
                    enclosureType = "audio/mpeg",
                ),
                FeedItem(
                    id = "episode-2",
                    feedId = feedId,
                    title = "Episode Two",
                    itemGuid = "g-episode-2",
                    enclosureUrl = "https://example.com/ep2.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
        val viewModel = createViewModel()
        viewModel.setShowUnreadOnly(false)
        viewModel.uiState.first { !it.showUnreadOnly && it.articles.size == 4 }
        viewModel.toggleSelection("episode-1")
        viewModel.toggleSelection("episode-2")
        viewModel.toggleSelection("read-1")
        viewModel.uiState.first { it.selectedIds.size == 3 }

        viewModel.addSelectedToQueue()
        viewModel.uiState.first { !it.isSelectionMode }

        val queue = queueRepository.observeQueue().first { it.size == 2 }
        assertEquals(setOf("episode-1", "episode-2"), queue.map { it.item.id }.toSet())
    }

    @Test
    fun deleteSelected_removesItemsAndClearsSelection() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.setShowUnreadOnly(false)
        viewModel.uiState.first { !it.showUnreadOnly && it.articles.size == 2 }
        viewModel.toggleSelection("read-1")
        viewModel.uiState.first { it.isSelectionMode }

        viewModel.deleteSelected()
        viewModel.uiState.first { !it.isSelectionMode }

        val items = repository.observeItems(feedId).first { it.size == 1 }
        assertEquals(listOf("unread-1"), items.map { it.id })
    }

    /**
     * Regression coverage for issue #156 ("app crashes interacting with feed items during a feed
     * refresh", reported case: marking a large number of checked episodes as read while feeds
     * refreshed in the background). No stack trace was ever captured, and this couldn't actually
     * be reproduced (12 clean stress runs across this test and the repository/engine-level
     * equivalent in FeedUpdateEngineTest), but the scenario -- a bulk mark-read racing a refresh's
     * trim-to-itemsToKeep step on the very items being marked -- is real and worth permanent
     * coverage against regressing.
     */
    @Test
    fun bulkMarkReadWhileRefreshTrimsSameFeed() = runTest(testDispatcher) {
        settingsDataStore.setMaxArticles(5)
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            feedId = repository.subscribe(Feed(title = "Big Feed", feedUrl = url))
            val bulkItems = (1..30).map { i ->
                FeedItem(id = "bulk-$i", feedId = feedId, itemGuid = "bg$i", title = "Item $i", isRead = false)
            }
            repository.insertItems(bulkItems)
            val itemsXml = (1..30).joinToString("\n") { i ->
                """
                <item>
                  <title>Item $i</title>
                  <link>https://example.com/item-$i</link>
                  <guid>bg$i</guid>
                  <description>Body</description>
                  <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                </item>
                """.trimIndent()
            }
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel><title>Big Feed</title><link>https://example.com</link>
                    <description>desc</description>
                    $itemsXml
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            val viewModel = createViewModel()

            var caught: Throwable? = null
            val collectJob = launch {
                try {
                    viewModel.uiState.collect {}
                } catch (t: Throwable) {
                    caught = t
                }
            }

            viewModel.setShowUnreadOnly(false)
            viewModel.uiState.first { !it.showUnreadOnly && it.articles.size == 30 }
            bulkItems.forEach { viewModel.toggleSelection(it.id) }
            viewModel.uiState.first { it.selectedIds.size == 30 }

            val refreshJob = launch {
                try {
                    viewModel.refresh()
                } catch (t: Throwable) {
                    caught = t
                }
            }
            val markReadJob = launch {
                try {
                    repeat(20) { viewModel.markSelectedRead(true) }
                } catch (t: Throwable) {
                    caught = t
                }
            }
            refreshJob.join()
            markReadJob.join()
            viewModel.uiState.first { !it.isRefreshing }
            collectJob.cancel()

            if (caught != null) throw AssertionError("Bulk markSelectedRead concurrent with refresh() threw: $caught", caught)
        } finally {
            server.shutdown()
        }
    }
}
