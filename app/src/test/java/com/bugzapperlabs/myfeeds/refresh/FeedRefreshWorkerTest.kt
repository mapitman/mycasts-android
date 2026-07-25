package com.bugzapperlabs.myfeeds.refresh

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.bugzapperlabs.myfeeds.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.DownloadScheduling
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * Deliberately doesn't use MockWebServer/a real HTTP round trip (unlike FeedUpdateEngineTest,
 * which already covers fetch/parse thoroughly): this worker's own job is only to call
 * FeedUpdateEngine.updateFeeds for every subscribed feed and tolerate per-feed failures, which an
 * unreachable URL exercises just as well without the extra OkHttpClient/socket/thread overhead of
 * a live listener in every Robolectric-hosted run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRefreshWorkerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
            },
            settingsDataStore = settingsDataStore,
        )
        queueRepository = QueueRepository(db.queueDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun doWork_toleratesPerFeedFailureAndStillSucceeds() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repository.subscribe(Feed(title = "A Feed", feedUrl = "http://localhost:1/feed.xml"))
        val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

        val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
            .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_postsNotification_whenNewItemsFoundAndSettingEnabled() = runTest {
        settingsDataStore.setNotifyOnNewItems(true)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            repository.subscribe(Feed(title = "A Feed", feedUrl = url))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel>
                      <title>A Feed</title>
                      <link>https://example.com</link>
                      <description>desc</description>
                      <item>
                        <title>New Article</title>
                        <link>https://example.com/1</link>
                        <guid>guid-1</guid>
                        <description>Body</description>
                        <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()

            worker.doWork()

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val shadowManager = Shadows.shadowOf(notificationManager)
            assertTrue(shadowManager.allNotifications.isNotEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doWork_doesNotNotify_whenSettingDisabled() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            repository.subscribe(Feed(title = "A Feed", feedUrl = url))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel>
                      <title>A Feed</title>
                      <link>https://example.com</link>
                      <description>desc</description>
                      <item>
                        <title>New Article</title>
                        <link>https://example.com/1</link>
                        <guid>guid-1</guid>
                        <description>Body</description>
                        <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()

            worker.doWork()

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val shadowManager = Shadows.shadowOf(notificationManager)
            assertTrue(shadowManager.allNotifications.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doWork_autoQueueEnabled_addsNewEpisodesAndEnforcesCap() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            val feedId = repository.subscribe(
                Feed(title = "A Podcast", feedUrl = url, autoQueueEnabled = true, autoQueueMaxCount = 1),
            )
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
                      <item>
                        <title>Episode 2</title>
                        <link>https://example.com/2</link>
                        <guid>guid-2</guid>
                        <description>Body</description>
                        <pubDate>Tue, 04 Jun 2013 11:05:30 GMT</pubDate>
                        <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="1" />
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()
            worker.doWork()

            // Cap = 1: both new episodes get auto-queued, then eviction trims back down to 1 --
            // whichever survives, it must belong to this feed and the queue must not exceed the cap.
            val queue = queueRepository.observeQueue().first()
            assertEquals(1, queue.size)
            assertEquals(feedId, queue.single().item.feedId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doWork_nonPodcastFeed_doesNotQueueNewEpisodes() = runTest {
        // Auto-queue is podcast-only functionality -- an ordinary article feed never gets it,
        // regardless of the new-podcast default (issue #137).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            repository.subscribe(Feed(title = "An Article Feed", feedUrl = url))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel>
                      <title>An Article Feed</title>
                      <link>https://example.com</link>
                      <description>desc</description>
                      <item>
                        <title>Article 1</title>
                        <link>https://example.com/1</link>
                        <guid>guid-1</guid>
                        <description>Body</description>
                        <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                      </item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()
            worker.doWork()

            assertTrue(queueRepository.observeQueue().first().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doWork_podcastFeedAutoQueueExplicitlyDisabled_doesNotQueueNewEpisodes() = runTest {
        // issue #137's new-podcast default only applies on a feed's first-ever fetch (lastGet ==
        // null) -- once that's happened, a user who's since turned auto-queue back off must stay
        // off on later refreshes, not get silently re-enabled.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            val feedId = repository.subscribe(
                Feed(title = "A Podcast", feedUrl = url, lastGet = 1L, autoQueueEnabled = false),
            )
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
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()
            worker.doWork()

            assertTrue(queueRepository.observeQueue().first().isEmpty())
            assertFalse(repository.getFeed(feedId)!!.autoQueueEnabled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doWork_newPodcastSubscription_defaultsToAutoQueueCappedAtFive() = runTest {
        // issue #137: a podcast's first-ever fetch should default it to auto-queuing, capped at 5
        // episodes, rather than the app-wide autoQueueEnabled=false/unlimited-cap defaults.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/feed.xml").toString()
            val feedId = repository.subscribe(Feed(title = "A Podcast", feedUrl = url))
            // trimMargin (not trimIndent) here: the dynamically-generated, already-trimmed `items`
            // fragment has its own (zero) indentation, which would drag trimIndent's computed
            // common-minimum-indentation for the *outer* template down to zero too, leaving the
            // leading whitespace on the "<?xml ...?>" line intact and breaking the XML prolog.
            // trimMargin strips only lines that start with "|", leaving interpolated content alone.
            val items = (1..6).joinToString("\n") { n ->
                """
                |<item>
                |  <title>Episode $n</title>
                |  <link>https://example.com/$n</link>
                |  <guid>guid-$n</guid>
                |  <description>Body</description>
                |  <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                |  <enclosure url="https://example.com/ep$n.mp3" type="audio/mpeg" length="1" />
                |</item>
                """.trimMargin()
            }
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<rss version="2.0"><channel>
                    |  <title>A Podcast</title>
                    |  <link>https://example.com</link>
                    |  <description>desc</description>
                    |  $items
                    |</channel></rss>
                    """.trimMargin(),
                ),
            )
            val engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)

            val worker = TestListenableWorkerBuilder<FeedRefreshWorker>(context)
                .setWorkerFactory(TestWorkerFactory(repository, engine, downloadRepository, queueRepository, settingsDataStore))
                .build()
            worker.doWork()

            val feed = repository.getFeed(feedId)!!
            assertTrue(feed.autoQueueEnabled)
            assertEquals(5, feed.autoQueueMaxCount)
            assertEquals(5, queueRepository.observeQueue().first().size)
        } finally {
            server.shutdown()
        }
    }

    private class TestWorkerFactory(
        private val repository: FeedRepository,
        private val engine: FeedUpdateEngine,
        private val downloadRepository: EnclosureDownloadRepository,
        private val queueRepository: QueueRepository,
        private val settingsDataStore: SettingsDataStore,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ) = FeedRefreshWorker(
            appContext,
            workerParameters,
            repository,
            engine,
            AutoQueueAndDownloadEnforcer(repository, downloadRepository, queueRepository),
            settingsDataStore,
            FeedRefreshState(),
        )
    }
}
