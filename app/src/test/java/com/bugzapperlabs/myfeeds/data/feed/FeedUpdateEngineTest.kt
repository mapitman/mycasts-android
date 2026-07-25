package com.bugzapperlabs.myfeeds.data.feed

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedUpdateEngineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var engine: FeedUpdateEngine

    private fun rssWithItems(vararg items: Pair<String, String>): String {
        val itemsXml = items.joinToString("\n") { (guid, title) ->
            """
            <item>
              <title>$title</title>
              <link>https://example.com/$guid</link>
              <guid>$guid</guid>
              <description>Body for $title</description>
              <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
            </item>
            """.trimIndent()
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Test Feed</title>
              <link>https://example.com</link>
              <description>desc</description>
              $itemsXml
            </channel></rss>
        """.trimIndent().trim()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun subscribeFeed(itemsToKeep: Int? = null): Feed {
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(Feed(title = "Test Feed", feedUrl = url, itemsToKeep = itemsToKeep))
        return repository.getFeed(feedId)!!
    }

    @Test
    fun updateFeed_firstRun_insertsAllItemsAsNew() = runTest {
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(2, (result as FeedUpdateResult.Success).newItemCount)
        assertEquals(2, repository.observeItems(feed.id).first().size)
    }

    @Test
    fun updateFeed_secondRun_dedupsExistingItemsByGuidAndPreservesReadState() = runTest {
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))
        engine.updateFeed(feed)

        val firstItemId = repository.observeItems(feed.id).first().first { it.itemGuid == "guid-1" }.id
        repository.markRead(firstItemId, true)

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second", "guid-3" to "Third")),
        )
        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(1, (result as FeedUpdateResult.Success).newItemCount)
        val items = repository.observeItems(feed.id).first()
        assertEquals(3, items.size)
        val stillRead = items.first { it.itemGuid == "guid-1" }
        assertEquals(firstItemId, stillRead.id)
        assertTrue(stillRead.isRead)
    }

    @Test
    fun updateFeed_updatesLastGetTimestamp() = runTest {
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))

        engine.updateFeed(feed)

        val updated = repository.getFeed(feed.id)!!
        assertTrue(updated.lastGet != null && updated.lastGet!! > 0)
    }

    @Test
    fun updateFeed_backfillsFeedImageUrlFromLatestParse() = runTest {
        val feed = subscribeFeed()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>Test Feed</title>
                  <link>https://example.com</link>
                  <description>desc</description>
                  <image><url>https://example.com/logo.png</url></image>
                  ${"<item><title>First</title><link>https://example.com/guid-1</link><guid>guid-1</guid>" +
                    "<description>Body</description><pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate></item>"}
                </channel></rss>
                """.trimIndent(),
            ),
        )

        engine.updateFeed(feed)

        assertEquals("https://example.com/logo.png", repository.getFeed(feed.id)!!.imageUrl)
    }

    @Test
    fun updateFeed_backfillsBlankTitleFromParsedFeed() = runTest {
        // issue #219: an OPML outline with no title/text attribute imports with a blank Feed.title --
        // the next refresh should fill it in from the fetched feed's own <title>.
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(Feed(title = "", feedUrl = url))
        val feed = repository.getFeed(feedId)!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))

        engine.updateFeed(feed)

        assertEquals("Test Feed", repository.getFeed(feed.id)!!.title)
    }

    @Test
    fun updateFeed_doesNotOverwriteExistingTitle() = runTest {
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))

        engine.updateFeed(feed)

        assertEquals("Test Feed", repository.getFeed(feed.id)!!.title)
    }

    @Test
    fun updateFeed_capsDescriptionLengthSoRowCannotExceedCursorWindowLimit() = runTest {
        // issue #234: a feed publishing a long-form full-text post large enough on its own made a
        // single row too big for Room/CursorWindow to read back, crashing the app.
        val feed = subscribeFeed()
        val hugeDescription = "x".repeat(500_000)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <title>Test Feed</title>
                  <link>https://example.com</link>
                  <description>desc</description>
                  <item>
                    <title>Huge Post</title>
                    <link>https://example.com/huge</link>
                    <guid>guid-huge</guid>
                    <description>$hugeDescription</description>
                    <pubDate>Mon, 03 Jun 2013 11:05:30 GMT</pubDate>
                  </item>
                </channel></rss>
                """.trimIndent(),
            ),
        )

        engine.updateFeed(feed)

        val stored = repository.observeItems(feed.id).first().single().description
        assertEquals(200_000, stored!!.length)
    }

    @Test
    fun updateFeed_trimsToItemsToKeepAfterPersisting() = runTest {
        val feed = subscribeFeed(itemsToKeep = 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(1, (result as FeedUpdateResult.Success).evictedItemIds.size)
        assertEquals(1, repository.observeItems(feed.id).first().size)
    }

    @Test
    fun updateFeed_noPerFeedItemsToKeep_fallsBackToGlobalMaxArticles() = runTest {
        // issue #82: null itemsToKeep means "use the app-wide default" (per Feed Properties'
        // display), not "unlimited" -- a feed relying on the global default was never trimmed.
        settingsDataStore.setMaxArticles(1)
        val feed = subscribeFeed(itemsToKeep = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(1, (result as FeedUpdateResult.Success).evictedItemIds.size)
        assertEquals(1, repository.observeItems(feed.id).first().size)
    }

    @Test
    fun updateFeed_fetchFailure_returnsFailureWithoutTouchingDb() = runTest {
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(500))

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Failure)
        assertEquals(0, repository.observeItems(feed.id).first().size)
    }

    @Test
    fun updateFeed_secondRun_doesNotDropAlreadyQueuedItemFromNextUp() = runTest {
        // issue #153: re-persisting an already-known item via INSERT OR REPLACE fired
        // queue_entries' ON DELETE CASCADE even though the item's id didn't change, silently
        // dropping it from Next Up on every subsequent refresh.
        val feed = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))
        engine.updateFeed(feed)
        val itemId = repository.observeItems(feed.id).first().first { it.itemGuid == "guid-1" }.id
        queueRepository.addToEnd(itemId)

        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))
        engine.updateFeed(feed)

        assertTrue(queueRepository.isQueued(itemId))
    }

    @Test
    fun updateFeed_doesNotClobberPlaybackSpeedChangedDuringFetch() = runTest {
        // issue #189: persist() used to write back a Feed built from the snapshot passed in at
        // the start of the refresh, silently reverting anything the user changed on that feed
        // (e.g. playback speed via the player) while the network fetch was still in flight.
        val feed = subscribeFeed()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { repository.updateFeed(repository.getFeed(feed.id)!!.copy(playbackSpeed = 1.75f)) }
                return MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First"))
            }
        }

        engine.updateFeed(feed)

        assertEquals(1.75f, repository.getFeed(feed.id)!!.playbackSpeed)
    }

    @Test
    fun updateFeeds_updatesMultipleFeedsConcurrentlyAndReturnsAllResults() = runTest {
        val feedA = subscribeFeed()
        val feedB = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("a-1" to "A1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("b-1" to "B1")))

        val results = engine.updateFeeds(listOf(feedA, feedB))

        assertEquals(2, results.size)
        assertTrue(results.all { it is FeedUpdateResult.Success })
        assertEquals(1, repository.observeItems(feedA.id).first().size)
        assertEquals(1, repository.observeItems(feedB.id).first().size)
    }

    /** issue #177: verifies the configured concurrency actually bounds in-flight fetches, not
     *  just that multiple feeds can update in the same [FeedUpdateEngine.updateFeeds] call. */
    @Test
    fun updateFeeds_respectsConfiguredConcurrencyLimit() = runTest {
        settingsDataStore.setFeedRefreshConcurrency(1)
        val feedA = subscribeFeed()
        val feedB = subscribeFeed()
        val activeRequests = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val active = activeRequests.incrementAndGet()
                maxObservedConcurrency.updateAndGet { current -> maxOf(current, active) }
                Thread.sleep(200)
                activeRequests.decrementAndGet()
                return MockResponse().setResponseCode(200).setBody(rssWithItems("a-1" to "A1"))
            }
        }

        engine.updateFeeds(listOf(feedA, feedB))

        assertEquals(1, maxObservedConcurrency.get())
    }
}
