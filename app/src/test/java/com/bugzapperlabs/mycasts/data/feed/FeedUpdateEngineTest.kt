package com.bugzapperlabs.mycasts.data.feed

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore, FeedRefreshLocks())
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
    fun updateFeed_reDiscoveringAPreviouslyTrimmedItem_doesNotCountItAsNewAgain() = runTest {
        // issue #60: trimToItemsToKeep deletes evicted rows outright, so if a feed's upstream RSS
        // keeps listing an episode after it's been trimmed out, the next refresh's GUID dedup no
        // longer finds it, re-inserts it as "new", and immediately re-evicts it again -- inflating
        // the new-episodes notification's count every single refresh instead of settling once.
        // newItemCount must exclude items evicted in the same cycle they were "discovered" in.
        fun rssWithDatedItems(vararg items: Triple<String, String, String>) = items.joinToString(
            separator = "\n",
            prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
                "<title>Test Feed</title><link>https://example.com</link><description>desc</description>",
            postfix = "</channel></rss>",
        ) { (guid, title, pubDate) ->
            "<item><title>$title</title><link>https://example.com/$guid</link><guid>$guid</guid>" +
                "<description>Body</description><pubDate>$pubDate</pubDate></item>"
        }

        val feed = subscribeFeed(itemsToKeep = 2)
        // Cycle 1: three items, oldest ("old") gets evicted immediately after being inserted.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("old", "Old", "Mon, 01 Jan 2024 00:00:00 GMT"),
                    Triple("mid", "Mid", "Tue, 02 Jan 2024 00:00:00 GMT"),
                    Triple("new", "New", "Wed, 03 Jan 2024 00:00:00 GMT"),
                ),
            ),
        )
        val firstResult = engine.updateFeed(feed) as FeedUpdateResult.Success
        assertEquals(2, firstResult.newItemCount)
        assertEquals(2, repository.observeItems(feed.id).first().size)

        // Cycle 2: the feed still lists "old" (a real-world feed's RSS often keeps historical
        // episodes), which was deleted by cycle 1's trim -- it re-appears as a DB insert, then gets
        // evicted again immediately since it's still the oldest of the three.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("old", "Old", "Mon, 01 Jan 2024 00:00:00 GMT"),
                    Triple("mid", "Mid", "Tue, 02 Jan 2024 00:00:00 GMT"),
                    Triple("new", "New", "Wed, 03 Jan 2024 00:00:00 GMT"),
                ),
            ),
        )
        val secondResult = engine.updateFeed(feed) as FeedUpdateResult.Success

        assertEquals(0, secondResult.newItemCount)
        val items = repository.observeItems(feed.id).first()
        assertEquals(2, items.size)
        assertEquals(setOf("mid", "new"), items.map { it.itemGuid }.toSet())
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
    fun updateFeed_concurrentRefreshesOfSameFeed_doNotDuplicateItemsOrExceedLimit() = runTest {
        // issue #70: two overlapping refreshes of the same feed (e.g. a manual pull-to-refresh
        // landing while the scheduled FeedRefreshWorker is also refreshing) used to race --
        // each's findByItemGuid check ran before the other's insert committed, so both inserted
        // their own fresh-UUID copy of the same episode, and itemsToKeep was badly overshot.
        val feed = subscribeFeed(itemsToKeep = 2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Slow enough that both concurrent fetches are in flight before either starts
                // persisting, exercising the race window this test guards against.
                Thread.sleep(50)
                return MockResponse().setResponseCode(200).setBody(
                    rssWithItems("guid-1" to "First", "guid-2" to "Second", "guid-3" to "Third"),
                )
            }
        }

        val (resultA, resultB) = coroutineScope {
            val a = async { engine.updateFeed(feed) }
            val b = async { engine.updateFeed(feed) }
            a.await() to b.await()
        }

        assertTrue(resultA is FeedUpdateResult.Success)
        assertTrue(resultB is FeedUpdateResult.Success)
        val items = repository.observeItems(feed.id).first()
        assertEquals(2, items.size)
        assertEquals(items.size, items.map { it.itemGuid }.distinct().size)
    }

    @Test
    fun updateFeed_noPerFeedItemsToKeep_fallsBackToGlobalMaxArticles() = runTest {
        // issue #82: null itemsToKeep means "use the app-wide default" (per Feed Properties'
        // display), not "unlimited" -- a feed relying on the global default was never trimmed.
        settingsDataStore.setMaxItemsPerFeed(1)
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

    /** issue #16: onFeedComplete backs the feed-refresh progress notification, so it must fire
     *  exactly once per feed with a running count that reaches the total. */
    @Test
    fun updateFeeds_invokesOnFeedCompleteOncePerFeedWithRunningCount() = runTest {
        val feedA = subscribeFeed()
        val feedB = subscribeFeed()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("a-1" to "A1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("b-1" to "B1")))
        val callCount = AtomicInteger(0)
        val observedTotals = mutableListOf<Int>()
        val observedCounts = mutableListOf<Int>()

        engine.updateFeeds(listOf(feedA, feedB)) { completedCount, totalCount ->
            callCount.incrementAndGet()
            observedCounts += completedCount
            observedTotals += totalCount
        }

        assertEquals(2, callCount.get())
        assertEquals(listOf(2, 2), observedTotals)
        assertEquals(setOf(1, 2), observedCounts.toSet())
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

    /**
     * Regression coverage for issue #156 ("app crashes interacting with feed items during a feed
     * refresh", reported case: marking a large number of checked episodes as read while feeds
     * refreshed in the background). No stack trace was ever captured, and this couldn't be
     * reproduced -- 12 clean stress runs across this test and the [EpisodeListViewModel]-level
     * equivalent, both before and after the concurrent-refresh hardening already landed for
     * issues #152/#189/#269/#276 -- but the scenario (a bulk item mutation racing a refresh's
     * trim-to-[itemsToKeep] step on the very rows being mutated) is a real, plausible one worth
     * permanent coverage against regressing.
     */
    @Test
    fun trimDuringRefresh_concurrentWithBulkMarkReadOnTrimmedItems_doesNotThrow() = runTest {
        settingsDataStore.setMaxItemsPerFeed(5)
        val feed = subscribeFeed()
        val existingItems = (1..30).map { i ->
            FeedItem(id = "item-$i", feedId = feed.id, itemGuid = "g$i", title = "Item $i", isRead = false)
        }
        repository.insertItems(existingItems)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems(*(1..30).map { "g$it" to "Item $it" }.toTypedArray())))

        var caught: Throwable? = null
        coroutineScope {
            val refreshJob = async { engine.updateFeed(repository.getFeed(feed.id)!!) }
            val markReadJob = launch {
                repeat(50) {
                    existingItems.forEach { item ->
                        try {
                            repository.markRead(item.id, true)
                        } catch (t: Throwable) {
                            caught = t
                        }
                    }
                }
            }
            try {
                refreshJob.await()
            } catch (t: Throwable) {
                caught = t
            }
            markReadJob.join()
        }

        if (caught != null) throw AssertionError("Concurrent markRead during refresh threw: $caught", caught)
    }

    /** See [trimDuringRefresh_concurrentWithBulkMarkReadOnTrimmedItems_doesNotThrow] -- same
     *  scenario but with a bulk delete instead of a bulk mark-read, another real user action that
     *  can target items a concurrent refresh is about to trim. */
    @Test
    fun trimDuringRefresh_concurrentWithBulkDeleteOfSoonToBeTrimmedItems_doesNotThrow() = runTest {
        settingsDataStore.setMaxItemsPerFeed(5)
        val feed = subscribeFeed()
        val existingItems = (1..30).map { i ->
            FeedItem(id = "item-$i", feedId = feed.id, itemGuid = "g$i", title = "Item $i", isRead = false)
        }
        repository.insertItems(existingItems)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems(*(1..30).map { "g$it" to "Item $it" }.toTypedArray())))

        var caught: Throwable? = null
        coroutineScope {
            val refreshJob = async { engine.updateFeed(repository.getFeed(feed.id)!!) }
            val deleteJob = launch {
                repeat(10) {
                    try {
                        repository.deleteItems(existingItems)
                    } catch (t: Throwable) {
                        caught = t
                    }
                }
            }
            try {
                refreshJob.await()
            } catch (t: Throwable) {
                caught = t
            }
            deleteJob.join()
        }

        if (caught != null) throw AssertionError("Concurrent deleteItems during refresh threw: $caught", caught)
    }
}
