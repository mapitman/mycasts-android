package com.bugzapperlabs.myfeeds.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.TrackedViewModelStore
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.opml.OpmlExporter
import com.bugzapperlabs.myfeeds.data.opml.OpmlImporter
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.refresh.FeedRefreshScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * The test dispatcher is shared between setMain and runTest so runTest's automatic
 * child-coroutine cleanup also covers the ViewModel's viewModelScope children.
 *
 * Skipped in CI only (see setUp): this file hangs reliably in GitHub Actions -- always timing out
 * at runTest's dispatch timeout (raised 60s -> 120s below, which still wasn't enough) -- but has
 * never reproduced locally despite many repeated full-suite and CPU-constrained runs. Tracked in
 * https://github.com/mapitman/myfeeds-android/issues/54; still runs normally outside CI.
 *
 * The quiescent tearDown below (clear + join before resetMain, via TrackedViewModelStore) fixes
 * one real source of cross-test corruption -- see that class's doc -- but CI still hangs even
 * with it in place, so the skip stays. See issue #215 for further diagnostics on this general
 * class of timing-dependent coroutine-test flakiness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var viewModel: SettingsViewModel

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()

    private val defaultFeedsRssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>A Feed</title>
          <link>https://example.com</link>
          <description>desc</description>
        </channel></rss>
    """.trimIndent()

    @Before
    fun setUp() {
        // See the class doc: this file hangs reliably in CI (issue #54) but never locally, so
        // it's skipped there for now rather than blocking unrelated work.
        assumeTrue("Skipped in CI: see issue #54", System.getenv("CI") == null)
        runTestBody()
    }

    private fun runTestBody() = runTest(testDispatcher, timeout = 120.seconds) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        // OPML import now validates each feed by actually fetching it (issue #231) -- default_feeds.opml
        // lists real external hosts, so every outgoing request is rewritten to this local server
        // regardless of its original host, and answered with a generic valid RSS body.
        server = MockWebServer()
        server.start()
        repeat(12) { server.enqueue(MockResponse().setResponseCode(200).setBody(defaultFeedsRssXml)) }
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
        val feedFetcher = FeedFetcher(httpClient)
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore)
        // Real WorkManager deadlocked when touched from Robolectric-hosted ViewModel tests (see
        // the scheduled-refresh PR description), so SettingsViewModel depends on the
        // FeedRefreshScheduling interface and this test uses a no-op fake instead.
        viewModel = SettingsViewModel(
            settingsDataStore = settingsDataStore,
            feedRepository = repository,
            opmlImporter = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore),
            opmlExporter = OpmlExporter(db.feedDao(), db.feedItemDao()),
            feedRefreshScheduler = object : FeedRefreshScheduling {
                override fun schedule(intervalMinutes: Long) {}
            },
            context = context,
        )
        viewModelStore.put("settings", viewModel)
    }

    @After
    fun tearDown() {
        // setUp bails out early via Assume when skipped in CI (see setUp/issue #54), leaving db
        // uninitialized -- guard against that so the skip doesn't itself register as a failure.
        if (!::db.isInitialized) return
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while clearAndJoin waits out in-flight ViewModel coroutines (issues #54/#60).
        runTest(testDispatcher) { viewModelStore.clearAndJoin() }
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun setUpdateIntervalMinutes_persistsAndReflectsInSettings() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.setUpdateIntervalMinutes(60)

        val settings = viewModel.settings.first { it.updateIntervalMinutes == 60L }
        assertEquals(60L, settings.updateIntervalMinutes)
    }

    @Test
    fun setArticleFontSize_persists() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.setArticleFontSize(FontSize.LARGE)

        val settings = viewModel.settings.first { it.articleFontSize == FontSize.LARGE }
        assertEquals(FontSize.LARGE, settings.articleFontSize)
    }

    @Test
    fun addDefaultFeeds_importsBundledOpml() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.addDefaultFeeds()

        val feeds = db.feedDao().observeAll().first { it.size == 12 }
        assertEquals(12, feeds.size)
        assertEquals("Imported 12 feeds", viewModel.addDefaultFeedsMessage.first { it != null })
    }

    @Test
    fun removeAllFeeds_deletesAllFeedsAndCascadesItems() = runTest(testDispatcher, timeout = 120.seconds) {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "g1")))

        viewModel.removeAllFeeds()

        val feeds = db.feedDao().observeAll().first { it.isEmpty() }
        assertTrue(feeds.isEmpty())
        assertTrue(db.feedItemDao().observeByFeed(feedId).first().isEmpty())
    }

    @Test
    fun clearPodcasts_clearsEnclosurePositions() = runTest(testDispatcher, timeout = 120.seconds) {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "g1", enclosurePosition = 42.0)),
        )

        viewModel.clearPodcasts()

        val item = db.feedItemDao().observeByFeed(feedId).first { it.first().enclosurePosition == null }.first()
        assertNull(item.enclosurePosition)
    }

    @Test
    fun resetSettings_restoresDefaultsWithoutTouchingFeeds() = runTest(testDispatcher, timeout = 120.seconds) {
        repository.subscribe(Feed(title = "A Feed"))
        viewModel.setMaxArticles(99)
        viewModel.settings.first { it.maxArticles == 99 }

        viewModel.resetSettings()

        val settings = viewModel.settings.first { it.maxArticles != 99 }
        assertEquals(20, settings.maxArticles)
        assertEquals(1, db.feedDao().observeAll().first().size)
    }
}
