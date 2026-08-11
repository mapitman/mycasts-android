package com.bugzapperlabs.mycasts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.TrackedViewModelStore
import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.FeedRefreshLocks
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.opml.OpmlExporter
import com.bugzapperlabs.mycasts.data.opml.OpmlImporter
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.FontSize
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * This file used to be unconditionally skipped in CI (issue #77, formerly
 * https://github.com/mapitman/myfeeds-android/issues/54) -- it hung reliably in GitHub Actions
 * even at a 120s runTest timeout, but never reproduced locally despite many repeated
 * full-suite/CPU-constrained runs. Re-enabled to check whether that's still true after this
 * ViewModel family's various coroutine-timing fixes (issues #73/#75/#76) -- if it starts hanging
 * in CI again, re-add the `assumeTrue` skip that used to be in setUp() rather than raising the
 * timeout further, since a higher timeout never actually fixed it before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var viewModel: SettingsViewModel

    // DataStore's own internal write-ahead actor runs on whatever scope it's given -- left at the
    // library default (Dispatchers.IO + a scope DataStore owns itself), it isn't cancelled by
    // anything this test does, so it can still be alive after the test method returns (issue #77).
    // JUnit's TemporaryFolder rule deletes the backing file right after the test, so a still-live
    // actor racing that deletion -- more likely the busier a test is, e.g. the 12-feed OPML import
    // below -- was a plausible source of the "hangs in CI, never locally" pattern this class used
    // to be skipped for entirely. Handing DataStore this explicit, per-test scope means tearDown
    // can cancel it outright before the backing file goes away.
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        runTestBody()
    }

    private fun runTestBody() = runTest(testDispatcher, timeout = 120.seconds) {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        // OPML import now validates each feed by actually fetching it (issue #231) -- default_feeds.opml
        // lists real external hosts, so every outgoing request is rewritten to this local server
        // regardless of its original host, and answered with a generic valid RSS body.
        server = MockWebServer()
        server.start()
        repeat(12) { server.enqueue(MockResponse().setResponseCode(200).setBody(defaultFeedsRssXml)) }
        httpClient = OkHttpClient.Builder()
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
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore, FeedRefreshLocks())
        // Real WorkManager deadlocked when touched from Robolectric-hosted ViewModel tests (see
        // the scheduled-refresh PR description), so SettingsViewModel depends on the
        // FeedRefreshScheduling interface and this test uses a no-op fake instead.
        viewModel = SettingsViewModel(
            settingsDataStore = settingsDataStore,
            feedRepository = repository,
            opmlImporter = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore),
            opmlExporter = OpmlExporter(db.feedDao()),
            feedRefreshScheduler = object : FeedRefreshScheduling {
                override fun schedule(intervalMinutes: Long) {}
            },
            context = context,
        )
        viewModelStore.put("settings", viewModel)
    }

    @After
    fun tearDown() {
        // Inside runTest (same scheduler as Dispatchers.Main) so the scheduler keeps getting
        // pumped while clearAndJoin waits out in-flight ViewModel coroutines (issues #54/#60).
        runTest(testDispatcher) { viewModelStore.clearAndJoin() }
        // Explicitly torn down (issue #77), in this order, before TemporaryFolder's own cleanup
        // deletes the DataStore's backing file: DataStore's internal actor and OkHttp's dispatcher
        // executor/connection pool otherwise keep running past this test method's return with
        // nothing to stop them, which is a plausible source of the old "hangs in CI" symptom this
        // class used to be skipped for -- the busier a test (e.g. 12 concurrent feed fetches in
        // addDefaultFeeds_importsBundledOpml), the more there is left to still be running.
        dataStoreScope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
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
    fun setEpisodeDetailsFontSize_persists() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.setEpisodeDetailsFontSize(FontSize.LARGE)

        val settings = viewModel.settings.first { it.episodeDetailsFontSize == FontSize.LARGE }
        assertEquals(FontSize.LARGE, settings.episodeDetailsFontSize)
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
        viewModel.setMaxItemsPerFeed(99)
        viewModel.settings.first { it.maxItemsPerFeed == 99 }

        viewModel.resetSettings()

        val settings = viewModel.settings.first { it.maxItemsPerFeed != 99 }
        assertEquals(20, settings.maxItemsPerFeed)
        assertEquals(1, db.feedDao().observeAll().first().size)
    }
}
