package com.bugzapperlabs.mycasts.settings

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
import com.bugzapperlabs.mycasts.data.backup.AppBackupRepository
import com.bugzapperlabs.mycasts.data.opml.OpmlExporter
import com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator
import com.bugzapperlabs.mycasts.data.opml.OpmlImporter
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
 * never reproduced locally despite many repeated full-suite and CPU-constrained runs. Originally
 * tracked in https://github.com/mapitman/myfeeds-android/issues/54, carried forward as issue #77.
 *
 * The quiescent tearDown below (clear + join before resetMain, via TrackedViewModelStore) fixes
 * one real source of cross-test corruption -- see that class's doc -- but CI still hangs even
 * with it in place, so the skip stays. See issue #215 for further diagnostics on this general
 * class of timing-dependent coroutine-test flakiness.
 *
 * Re-investigated for issue #77 (2026-08-11) with the skip temporarily removed on a scratch
 * branch to gather real evidence rather than guessing further -- findings, so a future attempt
 * starts here instead of from zero:
 * - The hang only reproduces when `addDefaultFeeds_importsBundledOpml` (12 concurrent feed
 *   fetches via OpmlImporter) runs before some other, otherwise-trivial test in the same class --
 *   confirmed by isolating just the two originally-observed failing tests (passed cleanly) and
 *   then adding addDefaultFeeds back alone (reproduced the hang). addDefaultFeeds itself always
 *   passes fast; it's whatever runs *after* it, in the same Gradle test JVM fork, that hangs.
 * - Explicitly tearing down likely-shared resources (a dedicated, cancellable CoroutineScope for
 *   the DataStore instead of its default internal one; explicit OkHttpClient dispatcher/
 *   connection-pool shutdown; a dedicated Room query/transaction executor instead of the shared
 *   ArchTaskExecutor) reduced but did not eliminate the hang -- the specific test that hangs
 *   shifts between runs, which still points at some form of cross-test state leakage in the same
 *   JVM fork rather than something inherent to whichever test happens to fail.
 * - A thread-dump-on-hang watchdog showed the hang is NOT simple thread/resource exhaustion:
 *   every worker pool (Dispatchers.IO, Room's disk-io threads, the dedicated executors above) is
 *   idle, not stuck, at the time of the hang. The actual test-runner thread ("SDK 35 Main
 *   Thread") is parked inside runTest's own runBlocking, waiting on the test's coroutine body,
 *   which itself never resumes -- consistent with a write (e.g. via SettingsDataStore) never
 *   actually propagating back through `settings: StateFlow`, so a `.first { ... }` predicate
 *   waits on a condition that never becomes true. This needs an instrumented, targeted
 *   reproduction of that specific propagation path next, not more general resource-cleanup
 *   hygiene fixes.
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
    private lateinit var opmlImportCoordinator: OpmlImportCoordinator
    private lateinit var viewModel: SettingsViewModel

    // Cleared *and joined* in tearDown so no ViewModel coroutine is still in flight when
    // Dispatchers.resetMain runs -- see TrackedViewModelStore's doc for the full leak mechanics
    // behind the #54/#60 flakiness this prevents.
    private val viewModelStore = TrackedViewModelStore()

    // Includes an audio enclosure (issue #122: OpmlImporter now rejects feeds with no audio
    // episodes as not-a-podcast) so this is podcast-valid.
    private val defaultFeedsRssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>A Feed</title>
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
        repeat(7) { server.enqueue(MockResponse().setResponseCode(200).setBody(defaultFeedsRssXml)) }
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
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore, FeedRefreshLocks())
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val queueRepository = QueueRepository(db.queueDao())
        val enforcer = AutoQueueAndDownloadEnforcer(repository, downloadRepository, queueRepository)
        val opmlImporter = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore, enforcer)
        opmlImportCoordinator = OpmlImportCoordinator(opmlImporter, context)
        // Real WorkManager deadlocked when touched from Robolectric-hosted ViewModel tests (see
        // the scheduled-refresh PR description), so SettingsViewModel depends on the
        // FeedRefreshScheduling interface and this test uses a no-op fake instead.
        viewModel = SettingsViewModel(
            settingsDataStore = settingsDataStore,
            feedRepository = repository,
            opmlImportCoordinator = opmlImportCoordinator,
            opmlExporter = OpmlExporter(db.feedDao()),
            appBackupRepository = AppBackupRepository(repository, queueRepository, settingsDataStore),
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
        runTest(testDispatcher) {
            viewModelStore.clearAndJoin()
            // OpmlImportCoordinator runs on its own real (non-test-scheduler) scope (issue #105),
            // same reason DownloadFeedbackCoordinator needs this -- nothing should be left mid-flight
            // against db below.
            opmlImportCoordinator.cancelForTest()
        }
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
    fun setFontSize_persists() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.setFontSize(1.3f)

        val settings = viewModel.settings.first { it.fontSize == 1.3f }
        assertEquals(1.3f, settings.fontSize)
    }

    @Test
    fun addDefaultFeeds_importsBundledOpml() = runTest(testDispatcher, timeout = 120.seconds) {
        viewModel.addDefaultFeeds()

        val feeds = db.feedDao().observeAll().first { it.size == 7 }
        assertEquals(7, feeds.size)
        assertEquals("Imported 7 feeds", viewModel.addDefaultFeedsMessage.first { it != null })
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

    @Test
    fun enableAutoDownloadForExistingFeeds_enablesItOnEveryExistingFeed() = runTest(testDispatcher, timeout = 120.seconds) {
        repository.subscribe(Feed(title = "Feed A", autoDownloadEnabled = false))
        repository.subscribe(Feed(title = "Feed B", autoDownloadEnabled = false))

        viewModel.enableAutoDownloadForExistingFeeds()

        val feeds = db.feedDao().observeAll().first { feeds -> feeds.all { it.autoDownloadEnabled } }
        assertEquals(2, feeds.size)
    }
}
