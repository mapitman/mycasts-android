package com.bugzapperlabs.mycasts.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.settings.UNLIMITED_ITEMS_TO_KEEP
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun subscribe_sameFeedUrlTwice_returnsTheExistingFeedIdWithoutCreatingADuplicate() = runTest {
        // issue #140: a re-subscribe (search result, manual URL entry, OPML re-import) used to
        // insert an unconditional second Feed row for a feedUrl already subscribed, silently
        // splitting that podcast's episodes across two feedIds -- a queued episode under the
        // "other" row played fine from Next Up but was invisible on the podcast's own episode list.
        val firstId = repository.subscribe(Feed(title = "A Feed", feedUrl = "https://example.com/feed"))

        val secondId = repository.subscribe(Feed(title = "A Feed (again)", feedUrl = "https://example.com/feed"))

        assertEquals(firstId, secondId)
        assertEquals(1, repository.observeAllFeeds().first().size)
        // The existing feed's own settings/title must survive untouched, not get silently
        // overwritten by whatever blank Feed the caller built for the "new" subscription attempt.
        assertEquals("A Feed", repository.getFeed(firstId)?.title)
    }

    @Test
    fun subscribe_distinctFeedUrls_createsSeparateFeeds() = runTest {
        val firstId = repository.subscribe(Feed(title = "Feed A", feedUrl = "https://example.com/a"))

        val secondId = repository.subscribe(Feed(title = "Feed B", feedUrl = "https://example.com/b"))

        assertTrue(firstId != secondId)
        assertEquals(2, repository.observeAllFeeds().first().size)
    }

    @Test
    fun unsubscribe_removesFeedAndCascadesItems() = runTest {
        val feed = Feed(title = "A Feed")
        val feedId = repository.subscribe(feed)
        repository.insertItems(listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1")))

        repository.unsubscribe(feed.copy(id = feedId))

        assertNull(repository.getFeed(feedId))
        assertEquals(0, repository.observeItems(feedId).first().size)
    }

    @Test
    fun markRead_updatesUnreadCounts() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1"),
                FeedItem(id = "item-2", feedId = feedId, itemGuid = "guid-2"),
            ),
        )

        repository.markRead("item-1", true)

        assertEquals(1, repository.observeUnreadCount(feedId).first())
        assertEquals(1, repository.observeTotalUnreadCount().first())
    }

    @Test
    fun markAllRead_clearsUnreadCountForFeed() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1"),
                FeedItem(id = "item-2", feedId = feedId, itemGuid = "guid-2"),
            ),
        )

        repository.markAllRead(feedId)

        assertEquals(0, repository.observeUnreadCount(feedId).first())
    }

    @Test
    fun findByItemGuid_locatesExistingItemForDedup() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "guid-1")))

        val found = repository.findByItemGuid(feedId, "guid-1")

        assertEquals("item-1", found?.id)
        assertNull(repository.findByItemGuid(feedId, "missing-guid"))
    }

    @Test
    fun trimToItemsToKeep_deletesOldestBeyondLimitAndReturnsEvicted() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = 2))
        repository.insertItems(
            listOf(
                FeedItem(id = "oldest", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "middle", feedId = feedId, itemGuid = "g2", publishDate = 2L),
                FeedItem(id = "newest", feedId = feedId, itemGuid = "g3", publishDate = 3L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = 999)

        assertEquals(listOf("oldest"), evicted.map { it.id })
        val remaining = repository.observeItems(feedId).first().map { it.id }
        assertTrue(remaining.containsAll(listOf("middle", "newest")))
        assertEquals(2, remaining.size)
    }

    @Test
    fun trimToItemsToKeep_doesNotEvictQueuedItems() = runTest {
        // issue #125: episodes a user manually added to Next Up were being silently dropped
        // because trimToItemsToKeep deleted the underlying FeedItem, which cascade-deleted the
        // queue_entries row too.
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = 2))
        repository.insertItems(
            listOf(
                FeedItem(id = "oldest", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "middle", feedId = feedId, itemGuid = "g2", publishDate = 2L),
                FeedItem(id = "newest", feedId = feedId, itemGuid = "g3", publishDate = 3L),
            ),
        )
        db.queueDao().insert(QueueEntry(itemId = "oldest", position = 0, addedAt = 0L))

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = 999)

        assertEquals(emptyList<String>(), evicted.map { it.id })
        val remaining = repository.observeItems(feedId).first().map { it.id }
        assertTrue(remaining.containsAll(listOf("oldest", "middle", "newest")))
    }

    @Test
    fun trimToItemsToKeep_alwaysExemptItemOutsideTrueTopN_isNotProtected() = runTest {
        // issue #137: alwaysExempt must not force-keep a candidate that's actually old once judged
        // against the feed's full item set, even though the caller believed it was "new".
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = 1))
        repository.insertItems(
            listOf(
                FeedItem(id = "genuinely-newest", feedId = feedId, itemGuid = "g1", publishDate = 100L),
                FeedItem(id = "old-but-exempt-candidate", feedId = feedId, itemGuid = "g2", publishDate = 1L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(
            feedId,
            defaultItemsToKeep = 999,
            alwaysExempt = setOf("old-but-exempt-candidate"),
        )

        assertEquals(listOf("old-but-exempt-candidate"), evicted.map { it.id })
        assertEquals(listOf("genuinely-newest"), repository.observeItems(feedId).first().map { it.id })
    }

    @Test
    fun trimToItemsToKeep_alwaysExemptItemWithinTrueTopN_isStillProtected() = runTest {
        // Regression for issue #83/#134: a genuinely newest candidate must still survive
        // same-refresh eviction -- unaffected by issue #137's fix.
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = 1))
        repository.insertItems(
            listOf(
                FeedItem(id = "pre-existing-older", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "genuinely-newest-exempt", feedId = feedId, itemGuid = "g2", publishDate = 100L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(
            feedId,
            defaultItemsToKeep = 999,
            alwaysExempt = setOf("genuinely-newest-exempt"),
        )

        assertEquals(listOf("pre-existing-older"), evicted.map { it.id })
        assertEquals(listOf("genuinely-newest-exempt"), repository.observeItems(feedId).first().map { it.id })
    }

    @Test
    fun deduplicateItems_keepsMostRecentCopyWhenNoneQueued() = runTest {
        // issue #70 follow-up: the since-fixed concurrent-refresh race could insert multiple rows
        // sharing the same itemGuid, each with its own id.
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "dup-old", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "dup-new", feedId = feedId, itemGuid = "g1", publishDate = 2L),
                FeedItem(id = "unique", feedId = feedId, itemGuid = "g2", publishDate = 3L),
            ),
        )

        repository.deduplicateItems(feedId)

        val remaining = repository.observeItems(feedId).first().map { it.id }
        assertEquals(setOf("dup-new", "unique"), remaining.toSet())
    }

    @Test
    fun deduplicateItems_keepsTheQueuedCopyRegardlessOfPublishDate() = runTest {
        // A duplicate that got auto-queued during the race is otherwise permanently exempt from
        // trimToItemsToKeep's eviction -- consolidating onto a *different* surviving copy would
        // silently drop it from Next Up, so the queued copy must be the one kept.
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "dup-queued", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "dup-newer", feedId = feedId, itemGuid = "g1", publishDate = 2L),
            ),
        )
        db.queueDao().insert(QueueEntry(itemId = "dup-queued", position = 0, addedAt = 0L))

        repository.deduplicateItems(feedId)

        val remaining = repository.observeItems(feedId).first().map { it.id }
        assertEquals(listOf("dup-queued"), remaining)
        assertEquals("dup-queued", db.queueDao().firstItemId())
    }

    @Test
    fun deduplicateItems_noOpWhenNoDuplicates() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "item-1", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "item-2", feedId = feedId, itemGuid = "g2", publishDate = 2L),
            ),
        )

        repository.deduplicateItems(feedId)

        val remaining = repository.observeItems(feedId).first().map { it.id }
        assertEquals(setOf("item-1", "item-2"), remaining.toSet())
    }

    @Test
    fun setEnclosurePosition_persistsAndClears() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "g1")))

        repository.setEnclosurePosition("item-1", 42.5)
        assertEquals(42.5, repository.getItem("item-1")?.enclosurePosition)

        repository.setEnclosurePosition("item-1", null)
        assertNull(repository.getItem("item-1")?.enclosurePosition)
    }

    @Test
    fun trimToItemsToKeep_noOpWhenUnderLimit() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = 10))
        repository.insertItems(listOf(FeedItem(id = "item-1", feedId = feedId, itemGuid = "g1")))

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = 999)

        assertEquals(0, evicted.size)
    }

    @Test
    fun trimToItemsToKeep_noPerFeedOverride_fallsBackToDefault() = runTest {
        // issue #82: null itemsToKeep means "use the app-wide default", not "unlimited".
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = null))
        repository.insertItems(
            listOf(
                FeedItem(id = "oldest", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "newest", feedId = feedId, itemGuid = "g2", publishDate = 2L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = 1)

        assertEquals(listOf("oldest"), evicted.map { it.id })
        assertEquals(listOf("newest"), repository.observeItems(feedId).first().map { it.id })
    }

    @Test
    fun trimToItemsToKeep_perFeedUnlimited_keepsEverything() = runTest {
        // issue #302: UNLIMITED_ITEMS_TO_KEEP (0) means "never trim" -- naively passing 0 to
        // List.drop() would evict everything instead, so this guards the fix stays in place.
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = UNLIMITED_ITEMS_TO_KEEP))
        repository.insertItems(
            listOf(
                FeedItem(id = "oldest", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "newest", feedId = feedId, itemGuid = "g2", publishDate = 2L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = 1)

        assertEquals(emptyList<String>(), evicted.map { it.id })
        assertEquals(2, repository.observeItems(feedId).first().size)
    }

    @Test
    fun trimToItemsToKeep_globalDefaultUnlimited_keepsEverything() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed", itemsToKeep = null))
        repository.insertItems(
            listOf(
                FeedItem(id = "oldest", feedId = feedId, itemGuid = "g1", publishDate = 1L),
                FeedItem(id = "newest", feedId = feedId, itemGuid = "g2", publishDate = 2L),
            ),
        )

        val evicted = repository.trimToItemsToKeep(feedId, defaultItemsToKeep = UNLIMITED_ITEMS_TO_KEEP)

        assertEquals(emptyList<String>(), evicted.map { it.id })
        assertEquals(2, repository.observeItems(feedId).first().size)
    }

    @Test
    fun observeDownloadedItems_includesInProgressAndCompletedOnly() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(id = "not-downloaded", feedId = feedId, itemGuid = "g1"),
                FeedItem(id = "in-progress", feedId = feedId, itemGuid = "g2", downloadedBytes = 500L),
                FeedItem(id = "completed", feedId = feedId, itemGuid = "g3", downloadedFilePath = "/tmp/completed.mp3"),
            ),
        )

        val downloaded = repository.observeDownloadedItems().first()

        assertEquals(setOf("in-progress", "completed"), downloaded.map { it.item.id }.toSet())
        assertTrue(downloaded.all { it.feedTitle == "A Feed" })
    }
}
