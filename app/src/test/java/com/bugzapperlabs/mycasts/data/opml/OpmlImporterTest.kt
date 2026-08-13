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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpmlImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var importer: OpmlImporter
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var queueRepository: QueueRepository

    /** Includes an audio enclosure (issue #122: OpmlImporter now rejects feeds with no audio
     *  episodes as not-a-podcast) so this is podcast-valid by default -- most tests here just need
     *  a feed that imports successfully, not specific item content. */
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

    /** No audio enclosure -- a plain article/news feed, which OpmlImporter no longer imports
     *  (issue #122). The image enclosure mirrors real-world feeds like Windows Central/Sky News
     *  (see FeedItem.isPodcastEpisode's doc comment), which set <enclosure> for a featured image
     *  rather than audio. */
    private fun articleRssXml(title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>$title</title>
          <link>https://example.com</link>
          <description>desc</description>
          <item>
            <title>An Article</title>
            <link>https://example.com/1</link>
            <guid>guid-1</guid>
            <description>Body</description>
            <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
            <enclosure url="https://example.com/cover.jpg" type="image/jpeg" length="1" />
          </item>
        </channel></rss>
    """.trimIndent()

    /** Routes by request path so responses resolve correctly regardless of fetch concurrency/order. */
    private fun dispatchByPath(vararg routes: Pair<String, MockResponse>) {
        val byPath = routes.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                byPath[request.path] ?: MockResponse().setResponseCode(404)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
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
        queueRepository = QueueRepository(db.queueDao())
        val enforcer = AutoQueueAndDownloadEnforcer(repository, downloadRepository, queueRepository)
        importer = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore, enforcer)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun import_createsFeeds() = runTest {
        dispatchByPath("/feed" to MockResponse().setResponseCode(200).setBody(rssXml("Ars Technica")))
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder("Tech", listOf(OpmlFeed("Ars Technica", server.url("/feed").toString()))),
            ),
        )

        val result = importer.import(document)

        assertEquals(1, result.importedCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(listOf("Ars Technica"), feeds.map { it.title })
    }

    @Test
    fun import_newPodcastSubscription_autoQueuesItsFirstFetchEpisodes() = runTest {
        // issue #101: FeedUpdateEngine.persist() already defaults a new podcast feed to
        // autoQueueEnabled=true on its first fetch (issue #137), but nothing actually queued the
        // episodes from *that* fetch unless the caller separately ran
        // AutoQueueAndDownloadEnforcer.apply() afterward -- which OpmlImporter never did.
        dispatchByPath("/feed" to MockResponse().setResponseCode(200).setBody(rssXml("A Podcast")))
        val document = OpmlDocument(
            folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("A Podcast", server.url("/feed").toString())))),
        )

        importer.import(document)

        assertEquals(1, queueRepository.observeQueue().first().size)
    }

    @Test
    fun import_multipleFolders_returnsTotalFeedCount() = runTest {
        dispatchByPath(
            "/a" to MockResponse().setResponseCode(200).setBody(rssXml("A")),
            "/b" to MockResponse().setResponseCode(200).setBody(rssXml("B")),
            "/c" to MockResponse().setResponseCode(200).setBody(rssXml("C")),
        )
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(OpmlFeed("A", server.url("/a").toString()), OpmlFeed("B", server.url("/b").toString())),
                ),
                OpmlFolder("News", listOf(OpmlFeed("C", server.url("/c").toString()))),
            ),
        )

        val result = importer.import(document)

        assertEquals(3, result.importedCount)
    }

    @Test
    fun import_reportsProgressAsEachFeedCompletes() = runTest {
        // issue #105: onFeedComplete should report 0/total up front, then an increasing count as
        // each candidate resolves, ending at total/total -- regardless of concurrency/ordering.
        dispatchByPath(
            "/a" to MockResponse().setResponseCode(200).setBody(rssXml("A")),
            "/b" to MockResponse().setResponseCode(200).setBody(rssXml("B")),
            "/c" to MockResponse().setResponseCode(200).setBody(rssXml("C")),
        )
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(
                        OpmlFeed("A", server.url("/a").toString()),
                        OpmlFeed("B", server.url("/b").toString()),
                        OpmlFeed("C", server.url("/c").toString()),
                    ),
                ),
            ),
        )
        val progressUpdates = mutableListOf<ImportProgress>()

        importer.import(document) { completed, total -> progressUpdates.add(ImportProgress(completed, total)) }

        assertEquals(ImportProgress(0, 3), progressUpdates.first())
        assertEquals(ImportProgress(3, 3), progressUpdates.last())
        assertEquals(4, progressUpdates.size)
        assertEquals(listOf(3, 3, 3, 3), progressUpdates.map { it.totalCount })
        assertEquals(listOf(0, 1, 2, 3), progressUpdates.map { it.completedCount })
    }

    @Test
    fun import_emptyDocument_returnsZero() = runTest {
        val result = importer.import(OpmlDocument(folders = emptyList()))

        assertEquals(0, result.importedCount)
    }

    @Test
    fun import_skipsFeedsAlreadySubscribedByFeedUrl() = runTest {
        // issue #228: re-importing an OPML file that overlaps with existing subscriptions used to
        // insert an unconditional duplicate Feed row for every entry.
        dispatchByPath("/feed" to MockResponse().setResponseCode(200).setBody(rssXml("Ars Technica")))
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder("Tech", listOf(OpmlFeed("Ars Technica", server.url("/feed").toString()))),
            ),
        )
        importer.import(document)

        val second = importer.import(document)

        assertEquals(0, second.importedCount)
        assertEquals(1, second.alreadySubscribedCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(1, feeds.size)
    }

    @Test
    fun import_onlyImportsTheNewFeedsWhenSomeAlreadySubscribed() = runTest {
        dispatchByPath(
            "/a" to MockResponse().setResponseCode(200).setBody(rssXml("A")),
            "/b" to MockResponse().setResponseCode(200).setBody(rssXml("B")),
        )
        importer.import(
            OpmlDocument(folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("A", server.url("/a").toString()))))),
        )

        val result = importer.import(
            OpmlDocument(
                folders = listOf(
                    OpmlFolder(
                        "Tech",
                        listOf(
                            OpmlFeed("A", server.url("/a").toString()),
                            OpmlFeed("B", server.url("/b").toString()),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, result.importedCount)
        assertEquals(1, result.alreadySubscribedCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(listOf("A", "B"), feeds.map { it.title })
    }

    @Test
    fun import_twoCandidatesResolvingToTheSameFeedAfterDiscovery_onlyImportsOnce() = runTest {
        // issue #140: two OPML entries with distinct xmlUrls can still resolve to the very same
        // feed after HTML feed-link discovery (the only redirect-like case FeedFetcher's own
        // resolvedUrl actually reflects) -- the upfront seenUrls/findByFeedUrl checks only ever see
        // each candidate's original xmlUrl, so this collision wasn't caught until here.
        dispatchByPath(
            "/site-a" to MockResponse().setResponseCode(200).setBody(
                "<!doctype html><html><head>" +
                    "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"${server.url("/feed")}\">" +
                    "</head><body></body></html>",
            ),
            "/site-b" to MockResponse().setResponseCode(200).setBody(
                "<!doctype html><html><head>" +
                    "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"${server.url("/feed")}\">" +
                    "</head><body></body></html>",
            ),
            "/feed" to MockResponse().setResponseCode(200).setBody(rssXml("Shared Podcast")),
        )
        settingsDataStore.setFeedRefreshConcurrency(1)
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(
                        OpmlFeed("Site A", server.url("/site-a").toString()),
                        OpmlFeed("Site B", server.url("/site-b").toString()),
                    ),
                ),
            ),
        )

        val result = importer.import(document)

        assertEquals(1, result.importedCount)
        assertEquals(1, result.alreadySubscribedCount)
        assertEquals(0, result.invalidCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(listOf("Shared Podcast"), feeds.map { it.title })
    }

    @Test
    fun import_skipsInvalidFeedsAndReportsCount() = runTest {
        // issue #231: a feed URL that fails to fetch/parse shouldn't be subscribed at all.
        dispatchByPath(
            "/good" to MockResponse().setResponseCode(200).setBody(rssXml("Good Feed")),
            "/bad" to MockResponse().setResponseCode(404),
        )
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(
                        OpmlFeed("Good", server.url("/good").toString()),
                        OpmlFeed("Bad", server.url("/bad").toString()),
                    ),
                ),
            ),
        )

        val result = importer.import(document)

        assertEquals(1, result.importedCount)
        assertEquals(1, result.invalidCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(listOf("Good Feed"), feeds.map { it.title })
    }

    @Test
    fun import_skipsNonPodcastFeedsAndReportsCount() = runTest {
        // issue #122: a feed with no audio enclosures (a plain article/news feed) is treated the
        // same as an invalid/unreachable one -- excluded from the import, its provisional row
        // deleted, and counted in invalidCount.
        dispatchByPath(
            "/good" to MockResponse().setResponseCode(200).setBody(rssXml("Good Feed")),
            "/article" to MockResponse().setResponseCode(200).setBody(articleRssXml("An Article Feed")),
        )
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(
                        OpmlFeed("Good", server.url("/good").toString()),
                        OpmlFeed("Article", server.url("/article").toString()),
                    ),
                ),
            ),
        )

        val result = importer.import(document)

        assertEquals(1, result.importedCount)
        assertEquals(1, result.invalidCount)
        val feeds = db.feedDao().observeAll().first()
        assertEquals(listOf("Good Feed"), feeds.map { it.title })
    }

    @Test
    fun import_oneFeedThrowingUnexpectedly_doesNotCorruptConcurrentSiblingsTrim() = runTest {
        // issue #269: one candidate feed's *uncaught* exception (as opposed to a graceful
        // FeedFetchResult.Failure) used to cancel the whole `coroutineScope`, interrupting every
        // other concurrently in-flight feed mid-persist -- leaving them with items inserted but
        // never trimmed to itemsToKeep (root cause of a report that imported feeds weren't
        // honoring the max-articles-per-feed setting).
        settingsDataStore.setMaxItemsPerFeed(3)
        settingsDataStore.setFeedRefreshConcurrency(2)
        val goodItems = (1..10).joinToString(separator = "") { i ->
            "<item><title>Item $i</title><link>https://example.com/$i</link><guid>guid-$i</guid>" +
                "<description>Body $i</description><pubDate>Mon, 0${(i % 9) + 1} Jun 2013 11:05:30 GMT</pubDate>" +
                "<enclosure url=\"https://example.com/$i.mp3\" type=\"audio/mpeg\" length=\"1\" /></item>"
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/good") {
                    // Slow enough that the malformed sibling's request (below, fails near-instantly
                    // since it never leaves the client) resolves first, exercising the cancellation
                    // window mid-persist rather than before it starts.
                    Thread.sleep(50)
                    return MockResponse().setResponseCode(200).setBody(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
                            "<title>Good Feed</title><link>https://example.com</link><description>desc</description>" +
                            "$goodItems</channel></rss>",
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }
        val document = OpmlDocument(
            folders = listOf(
                OpmlFolder(
                    "Tech",
                    listOf(
                        OpmlFeed("Good", server.url("/good").toString()),
                        // Never leaves the HTTP client -- Request.Builder().url() throws
                        // IllegalArgumentException synchronously for this, which used to be
                        // uncaught (issue #269).
                        OpmlFeed("Bad", "not a valid url at all"),
                    ),
                ),
            ),
        )

        val result = importer.import(document)

        assertEquals(1, result.importedCount)
        assertEquals(1, result.invalidCount)
        val goodFeed = db.feedDao().observeAll().first().single { it.title == "Good Feed" }
        assertEquals(3, db.feedItemDao().observeByFeed(goodFeed.id).first().size)
    }

    @Test
    fun import_savesFeedsToTheDatabaseBeforeFetchingThem() = runTest {
        // issue #50: a candidate must be visible in the feed list as soon as it's parsed from the
        // OPML file, not only once its own network fetch happens to finish -- a large import used
        // to leave the screen looking mostly empty until each feed's fetch resolved one at a time.
        settingsDataStore.setFeedRefreshConcurrency(1)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fetchStarted.complete(Unit)
                runBlocking { releaseFetch.await() }
                return MockResponse().setResponseCode(200).setBody(rssXml("Slow Feed"))
            }
        }
        val document = OpmlDocument(
            folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("OPML Title", server.url("/feed").toString())))),
        )

        val importJob = launch { importer.import(document) }
        fetchStarted.await()

        // The fetch is still blocked mid-flight (releaseFetch hasn't fired yet), but the feed
        // should already be saved and visible with the OPML-provided title as a placeholder.
        val feedsWhileFetching = db.feedDao().observeAll().first()
        assertEquals(listOf("OPML Title"), feedsWhileFetching.map { it.title })

        releaseFetch.complete(Unit)
        importJob.join()

        val feedsAfterImport = db.feedDao().observeAll().first()
        assertEquals(listOf("Slow Feed"), feedsAfterImport.map { it.title })
    }

    @Test
    fun import_populatesTitleAndItemsImmediatelyFromTheValidatingFetch() = runTest {
        // issue #230: a newly imported feed shouldn't sit blank until the next scheduled refresh.
        dispatchByPath(
            "/feed" to MockResponse().setResponseCode(200).setBody(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>Fetched Title</title>
                  <link>https://example.com</link>
                  <description>desc</description>
                  <item>
                    <title>First</title>
                    <link>https://example.com/1</link>
                    <guid>guid-1</guid>
                    <enclosure url="https://example.com/1.mp3" type="audio/mpeg" length="1" />
                  </item>
                </channel></rss>
                """.trimIndent(),
            ),
        )
        val document = OpmlDocument(
            folders = listOf(OpmlFolder("Tech", listOf(OpmlFeed("OPML Title", server.url("/feed").toString())))),
        )

        importer.import(document)

        val feed = db.feedDao().observeAll().first().single()
        assertEquals("Fetched Title", feed.title)
        assertEquals(1, db.feedItemDao().observeByFeed(feed.id).first().size)
    }
}
