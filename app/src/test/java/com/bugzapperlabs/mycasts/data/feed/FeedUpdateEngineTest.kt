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
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /** Like [rssWithItems], but with an explicit, distinct `pubDate` per item so tests can assert
     *  which one a newest-by-publishDate cap keeps, rather than relying on item/list order. */
    private fun rssWithDatedItems(vararg items: Triple<String, String, String>) = items.joinToString(
        separator = "\n",
        prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>" +
            "<title>Test Feed</title><link>https://example.com</link><description>desc</description>",
        postfix = "</channel></rss>",
    ) { (guid, title, pubDate) ->
        "<item><title>$title</title><link>https://example.com/$guid</link><guid>$guid</guid>" +
            "<description>Body for $title</description><pubDate>$pubDate</pubDate></item>"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        val downloadRepository = EnclosureDownloadRepository(
            feedRepository = repository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        queueRepository = QueueRepository(db.queueDao(), repository, downloadRepository, settingsDataStore)
        engine = FeedUpdateEngine(FeedFetcher(OkHttpClient()), repository, settingsDataStore, FeedRefreshLocks())
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun subscribeFeed(itemsToKeep: Int? = null, path: String = "/feed.xml"): Feed {
        val url = server.url(path).toString()
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
    fun updateFeed_backfillsBlankFeedDescriptionFromParsedFeed() = runTest {
        // issue #170: an OPML-imported feed has no description at all (OPML outlines almost never
        // carry one) -- the next refresh should fill it in from the fetched feed's own <description>.
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(Feed(title = "Test Feed", feedUrl = url, description = null))
        val feed = repository.getFeed(feedId)!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))

        engine.updateFeed(feed)

        assertEquals("desc", repository.getFeed(feed.id)!!.description)
    }

    @Test
    fun updateFeed_keepsFeedDescriptionInSyncWithLatestFetch() = runTest {
        // issue #170: unlike title (which only backfills a blank value, protecting a user-editable
        // equivalent), there's no "userDescription" override to protect, so the stored description
        // always tracks the feed's latest fetched value -- the same policy as imageUrl.
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(Feed(title = "Test Feed", feedUrl = url, description = "Stale description"))
        val feed = repository.getFeed(feedId)!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))

        engine.updateFeed(feed)

        assertEquals("desc", repository.getFeed(feed.id)!!.description)
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
    fun updateFeed_firstFetch_capsToItemsToKeepBeforeInserting() = runTest {
        // issue #110: a feed's first fetch caps how many parsed items get built into rows and
        // inserted in the first place, rather than inserting the whole batch and relying on
        // trimToItemsToKeep afterward -- supersedes this test's old premise (issue #83's
        // protectedNewItemIds saving both items from eviction), since nothing exceeds itemsToKeep
        // post-insert anymore for there to be anything left to evict. Distinct, out-of-chronological-
        // order pubDates so the assertion also proves the *newest* item survives, not just
        // whichever one the feed's XML happened to list first.
        val feed = subscribeFeed(itemsToKeep = 1)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("guid-1", "Older", "Mon, 03 Jun 2013 11:05:30 GMT"),
                    Triple("guid-2", "Newer", "Wed, 05 Jun 2013 11:05:30 GMT"),
                ),
            ),
        )

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        val success = result as FeedUpdateResult.Success
        assertTrue("evictedItemIds=${success.evictedItemIds}", success.evictedItemIds.isEmpty())
        assertEquals(1, success.newItemIds.size)
        val stored = repository.observeItems(feed.id).first()
        assertEquals(listOf("guid-2"), stored.map { it.itemGuid })
    }

    @Test
    fun updateFeed_subsequentRefresh_stillTrimsNewItemsNormally() = runTest {
        // issue #110 only caps a feed's *first* fetch -- a later refresh (lastGet already set)
        // still inserts everything new and relies on trimToItemsToKeep afterward, same as before
        // that change (issue #82's original trim-to-itemsToKeep behavior is unaffected here).
        val feed = subscribeFeed(itemsToKeep = 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First")))
        engine.updateFeed(feed)
        val refreshedFeed = repository.getFeed(feed.id)!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))

        val result = engine.updateFeed(refreshedFeed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(1, (result as FeedUpdateResult.Success).evictedItemIds.size)
    }

    @Test
    fun updateFeed_autoQueueEnabled_subsequentRefresh_protectsUpToCapNewestFirst() = runTest {
        // issue #83's original mechanism, still live for a *subsequent* refresh (as opposed to
        // issue #110's first-fetch cap above): trimToItemsToKeep runs inside persist(), before the
        // caller ever gets a chance to run AutoQueueAndDownloadEnforcer.apply() against this
        // refresh's newItemIds -- a feed publishing more new episodes in one refresh than
        // itemsToKeep allows used to have the oldest of that very batch evicted immediately, so the
        // enforcer's later feedRepository.getItem(itemId) lookup silently found nothing and
        // skipped auto-queue (and so auto-download, per issue #219) entirely for those episodes,
        // with no error or trace of it happening.
        //
        // issue #134 then bounded that same-refresh exemption to itemsToKeep itself
        // (newest-by-publishDate) rather than exempting every new item unconditionally -- a feed
        // that (for whatever reason, e.g. a crash-interrupted earlier attempt) saw more "new"
        // items in one non-first refresh than itemsToKeep allows used to sit stuck over-cap until
        // a *later* refresh saw them as no-longer-new, instead of the cap holding immediately.
        // Distinct pubDates prove the *newest* of this refresh's own new items is the one that
        // survives, not just whichever the feed's XML happened to list first.
        //
        // The prior fetch deliberately returns zero items (rather than, say, one) -- keeps this
        // refresh's eviction candidates limited to just this refresh's own new items, since
        // FeedItemDao.getByFeed has no explicit ORDER BY and this test shouldn't depend on
        // whatever order an unrelated pre-existing item happens to come back in.
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(
            Feed(title = "Test Feed", feedUrl = url, itemsToKeep = 1, autoQueueEnabled = true),
        )
        val feed = repository.getFeed(feedId)!!
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems()))
        engine.updateFeed(feed)
        val refreshedFeed = repository.getFeed(feedId)!!
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("guid-1", "Older", "Mon, 03 Jun 2013 11:05:30 GMT"),
                    Triple("guid-2", "Newer", "Wed, 05 Jun 2013 11:05:30 GMT"),
                ),
            ),
        )

        val result = engine.updateFeed(refreshedFeed)

        assertTrue(result is FeedUpdateResult.Success)
        val success = result as FeedUpdateResult.Success
        assertEquals(2, success.newItemIds.size)
        // itemsToKeep=1: only the newest of this refresh's own new items is protected/survives,
        // never both -- the cap holds even during the temporary same-refresh exemption window.
        assertEquals(1, success.evictedItemIds.size)
        val stored = repository.observeItems(feed.id).first()
        assertEquals(listOf("guid-2"), stored.map { it.itemGuid })
    }

    @Test
    fun updateFeed_subsequentRefreshWithManyNewerItems_stillEvictsPreExistingOlderItemsDownToCap() = runTest {
        // Reproduces a real-world report: a feed already holding itemsToKeep's worth of older
        // items (from an earlier, unrelated fetch) gets refreshed, and this refresh's RSS returns
        // a batch of newer items that don't match any existing guid. The newer batch is correctly
        // capped by protectedNewItemIds (per the test above), but that alone doesn't prove the
        // *pre-existing older* items -- which aren't part of this refresh's newItemIds at all --
        // actually get evicted back down to the cap rather than lingering indefinitely just
        // because they predate this refresh.
        val feed = subscribeFeed(itemsToKeep = 2)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("old-1", "Old One", "Mon, 01 Jan 2024 00:00:00 GMT"),
                    Triple("old-2", "Old Two", "Tue, 02 Jan 2024 00:00:00 GMT"),
                ),
            ),
        )
        engine.updateFeed(feed)
        val refreshedFeed = repository.getFeed(feed.id)!!.copy(autoQueueEnabled = true)
        repository.updateFeed(refreshedFeed)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("new-1", "New One", "Wed, 01 Jan 2025 00:00:00 GMT"),
                    Triple("new-2", "New Two", "Thu, 02 Jan 2025 00:00:00 GMT"),
                    Triple("new-3", "New Three", "Fri, 03 Jan 2025 00:00:00 GMT"),
                ),
            ),
        )

        val result = engine.updateFeed(refreshedFeed)

        assertTrue(result is FeedUpdateResult.Success)
        val stored = repository.observeItems(feed.id).first()
        assertEquals("stored=${stored.map { it.itemGuid }}", 2, stored.size)
        assertEquals(setOf("new-2", "new-3"), stored.map { it.itemGuid }.toSet())
    }

    @Test
    fun updateFeed_subsequentRefreshWithOlderReissuedItem_doesNotDisplaceGenuinelyNewerStoredItem() = runTest {
        // issue #137: reproduces the reported bug -- a refresh can introduce a "new" row (no
        // existing itemGuid match) that's actually OLDER by publishDate than what the feed already
        // has stored, e.g. a podcast host migration reissuing a back-catalog episode under a
        // changed guid. Before this fix, protectedNewItemIds ranked this refresh's new rows only
        // against each other (contrast the "protectsUpToCapNewestFirst" test above, where there was
        // nothing else to rank against), so a lone reissued old episode -- the only "new" item this
        // refresh, hence trivially "newest" among a set of one -- got force-kept over the genuinely
        // newer already-stored item instead of being evicted.
        val url = server.url("/feed.xml").toString()
        val feedId = repository.subscribe(
            Feed(title = "Test Feed", feedUrl = url, itemsToKeep = 1),
        )
        val feed = repository.getFeed(feedId)!!
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(Triple("existing-newest", "Existing Newest", "Fri, 03 Jan 2025 00:00:00 GMT")),
            ),
        )
        engine.updateFeed(feed)
        val refreshedFeed = repository.getFeed(feedId)!!
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(
                    Triple("existing-newest", "Existing Newest", "Fri, 03 Jan 2025 00:00:00 GMT"),
                    Triple("reissued-old", "Reissued Old Episode", "Mon, 01 Jan 2024 00:00:00 GMT"),
                ),
            ),
        )

        val result = engine.updateFeed(refreshedFeed)

        assertTrue(result is FeedUpdateResult.Success)
        val success = result as FeedUpdateResult.Success
        // "existing-newest" matches by guid so it's not new; only "reissued-old" is.
        assertEquals(1, success.newItemIds.size)
        assertEquals(1, success.evictedItemIds.size)
        val stored = repository.observeItems(feed.id).first()
        assertEquals(listOf("existing-newest"), stored.map { it.itemGuid })
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
        // Evicted count is 0, not 1, since issue #110's first-fetch cap already applies the same
        // global-fallback resolution before insert, leaving nothing left over to trim afterward.
        settingsDataStore.setMaxItemsPerFeed(1)
        val feed = subscribeFeed(itemsToKeep = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssWithItems("guid-1" to "First", "guid-2" to "Second")))

        val result = engine.updateFeed(feed)

        assertTrue(result is FeedUpdateResult.Success)
        assertEquals(0, (result as FeedUpdateResult.Success).evictedItemIds.size)
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
        // Distinct paths (issue #140): subscribe() now dedupes by feedUrl, so two feeds in the
        // same test need different URLs, same as any two genuinely different podcasts would have.
        val feedA = subscribeFeed(path = "/feed-a.xml")
        val feedB = subscribeFeed(path = "/feed-b.xml")
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
        // Distinct paths (issue #140): see updateFeeds_updatesMultipleFeedsConcurrentlyAndReturnsAllResults.
        val feedA = subscribeFeed(path = "/feed-a.xml")
        val feedB = subscribeFeed(path = "/feed-b.xml")
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
        // Distinct paths (issue #140): see updateFeeds_updatesMultipleFeedsConcurrentlyAndReturnsAllResults.
        val feedA = subscribeFeed(path = "/feed-a.xml")
        val feedB = subscribeFeed(path = "/feed-b.xml")
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

    @Test
    fun updateFeed_downloadedItem_isNotEvictedAndFileSurvives() = runTest {
        // issue #200: trimToItemsToKeep now exempts downloaded episodes the same way it already
        // exempted queued ones -- an aged-out downloaded item must survive the trim, and so must
        // its file, rather than being silently evicted and orphaned/deleted (the prior behavior
        // issue #176 only papered over by cleaning up the file after the fact).
        val feed = subscribeFeed(itemsToKeep = 1)
        val downloadedFile = tempFolder.newFile("old-episode.mp3")
        assertTrue(downloadedFile.exists())
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "old-item",
                    feedId = feed.id,
                    itemGuid = "old",
                    publishDate = 1L,
                    autoDownloaded = true,
                    downloadedFilePath = downloadedFile.absolutePath,
                ),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                rssWithDatedItems(Triple("new", "New", "Wed, 03 Jan 2024 00:00:00 GMT")),
            ),
        )

        val result = engine.updateFeed(repository.getFeed(feed.id)!!) as FeedUpdateResult.Success

        assertEquals(emptyList<String>(), result.evictedItemIds)
        assertTrue("expected the downloaded file to survive", downloadedFile.exists())
    }
}
