package com.bugzapperlabs.mycasts.podcastdetails

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.addfeed.AddFeedUiState
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastDetailsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()
    private var nextViewModelKey = 0

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var feedFetcher: FeedFetcher
    private lateinit var feedUpdateEngine: FeedUpdateEngine
    private lateinit var context: android.content.Context

    private val rssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>A Podcast</title>
          <link>https://example.com</link>
          <description>desc</description>
          <item>
            <title>Episode One</title>
            <link>https://example.com/episode-1</link>
            <guid>episode-1</guid>
            <description>Episode body</description>
            <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
          </item>
        </channel></rss>
    """.trimIndent()

    private fun createViewModel(feedUrl: String, title: String? = null): PodcastDetailsViewModel =
        PodcastDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("feedUrl" to feedUrl, "title" to title)),
            feedFetcher = feedFetcher,
            feedRepository = repository,
            feedUpdateEngine = feedUpdateEngine,
            context = context,
        ).also { viewModelStore.put("podcastDetails-${nextViewModelKey++}", it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val settingsDataStore = SettingsDataStore(dataStore)
        val httpClient = OkHttpClient()
        feedFetcher = FeedFetcher(httpClient)
        feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore)
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while clearAndJoin waits out in-flight ViewModel coroutines (issues #54/#60).
        runTest(testDispatcher) { viewModelStore.clearAndJoin() }
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun init_fetchesFeedAndExposesEpisodesAndSiteUrl() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))

        val viewModel = createViewModel(server.url("/feed.xml").toString())
        val state = viewModel.previewState.first { it !is PodcastPreviewState.Loading }

        assertTrue("expected Loaded but got $state", state is PodcastPreviewState.Loaded)
        val loaded = state as PodcastPreviewState.Loaded
        assertEquals("A Podcast", loaded.feed.title)
        assertEquals("https://example.com", loaded.feed.siteUrl)
        assertEquals(listOf("Episode One"), loaded.feed.items.map { it.title })
    }

    @Test
    fun init_fetchFailure_showsError() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(404))

        val viewModel = createViewModel(server.url("/missing.xml").toString())
        val state = viewModel.previewState.first { it !is PodcastPreviewState.Loading }

        assertTrue(state is PodcastPreviewState.Error)
    }

    @Test
    fun subscribe_afterLoad_subscribesAndPersistsEpisode() = runTest(testDispatcher) {
        // Fetched once for the preview, once more inside FeedUpdateEngine.updateFeed to persist items.
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))

        val viewModel = createViewModel(server.url("/feed.xml").toString())
        viewModel.previewState.first { it !is PodcastPreviewState.Loading }

        viewModel.subscribe()
        val subscribeState = viewModel.subscribeState.first { it !is AddFeedUiState.Idle && it !is AddFeedUiState.Loading }

        assertTrue("expected Success but got $subscribeState", subscribeState is AddFeedUiState.Success)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(1, feeds.size)
        assertEquals("A Podcast", feeds.single().title)
        val items = db.feedItemDao().observeByFeed(feeds.single().id).first()
        assertEquals(1, items.size)
    }
}
