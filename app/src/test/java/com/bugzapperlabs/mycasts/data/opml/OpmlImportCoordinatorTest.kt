package com.bugzapperlabs.mycasts.data.opml

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.FeedRefreshLocks
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * [OpmlImportCoordinator] runs on its own real (non-test-scheduler) coroutine scope (issue #271) --
 * assertions here wait on its exposed flows via real suspension ([first]) rather than virtual-time
 * advancement, same as [com.bugzapperlabs.mycasts.download.DownloadFeedbackCoordinatorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpmlImportCoordinatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var coordinator: OpmlImportCoordinator

    /** Includes an audio enclosure (issue #122: OpmlImporter now rejects feeds with no audio
     *  episodes as not-a-podcast) so this is podcast-valid by default. */
    private fun rssXml(title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>$title</title>
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
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val settingsDataStore = SettingsDataStore(dataStore)
        val httpClient = OkHttpClient()
        val feedFetcher = FeedFetcher(httpClient)
        val repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore, FeedRefreshLocks())
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = settingsDataStore,
        )
        val enforcer = AutoQueueAndDownloadEnforcer(repository, downloadRepository, QueueRepository(db.queueDao()))
        val opmlImporter = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore, enforcer)
        coordinator = OpmlImportCoordinator(opmlImporter, context)
    }

    @After
    fun tearDown() = runTest {
        coordinator.cancelForTest()
        db.close()
        server.shutdown()
    }

    @Test
    fun startImport_reportsProgressThenClearsItOnceResultIsSet() = runTest {
        // The fetch is gated on releaseFetch so the assertion below can deterministically observe
        // progress mid-flight -- a real (non-gated) response can resolve fast enough on
        // OpmlImportCoordinator's own real scope that a plain `progress.first { it != null }` here
        // could race past the single 0/1 -> null transition and see only the final `null`.
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fetchStarted.complete(Unit)
                runBlocking { releaseFetch.await() }
                return MockResponse().setResponseCode(200).setBody(rssXml("A Feed"))
            }
        }
        val document = OpmlDocument(
            folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("A Feed", server.url("/feed").toString())))),
        )

        coordinator.startImport(document)
        fetchStarted.await()

        val progress = coordinator.progress.value
        assertEquals(ImportProgress(0, 1), progress)

        releaseFetch.complete(Unit)
        val result = coordinator.result.first { it != null }
        assertEquals("Imported 1 feeds", result)
        assertNull(coordinator.progress.value)
    }

    @Test
    fun consumeResult_clearsResult() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml("A Feed")))
        val document = OpmlDocument(
            folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("A Feed", server.url("/feed").toString())))),
        )
        coordinator.startImport(document)
        coordinator.result.first { it != null }

        coordinator.consumeResult()

        assertNull(coordinator.result.value)
    }
}
