package com.bugzapperlabs.mycasts.data.opml

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
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

    private fun rssXml(title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>$title</title>
          <link>https://example.com</link>
          <description>desc</description>
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
        val feedUpdateEngine = FeedUpdateEngine(feedFetcher, repository, settingsDataStore)
        importer = OpmlImporter(db.feedDao(), feedFetcher, feedUpdateEngine, settingsDataStore)
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
                "<description>Body $i</description><pubDate>Mon, 0${(i % 9) + 1} Jun 2013 11:05:30 GMT</pubDate></item>"
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
