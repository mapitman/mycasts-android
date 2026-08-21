package com.bugzapperlabs.mycasts.data.feed

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.AutoQueuePosition
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import kotlinx.coroutines.test.runTest
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

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * Covers the auto-queue side of [AutoQueueAndDownloadEnforcer] specifically -- in particular
 * issue #166's per-feed choice of adding newly-fetched episodes to the top vs. the bottom of the
 * Next Up queue. [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorkerTest] already covers the
 * bottom-of-queue (default) path end-to-end via a real HTTP fetch; these tests construct
 * [FeedUpdateResult.Success] directly instead, since the position-choice logic lives entirely in
 * this class and doesn't need a real feed fetch/parse round trip to exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoQueueAndDownloadEnforcerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var enforcer: AutoQueueAndDownloadEnforcer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        queueRepository = QueueRepository(db.queueDao())
        enforcer = AutoQueueAndDownloadEnforcer(feedRepository, downloadRepository, queueRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun subscribeAndInsertEpisode(
        itemId: String,
        autoQueuePosition: AutoQueuePosition,
    ): Long {
        val feedId = feedRepository.subscribe(
            Feed(title = "A Podcast", autoQueueEnabled = true, autoQueuePosition = autoQueuePosition),
        )
        feedRepository.insertItems(
            listOf(
                FeedItem(
                    id = itemId,
                    feedId = feedId,
                    title = "Episode",
                    itemGuid = itemId,
                    enclosureUrl = "https://example.com/$itemId.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
        return feedId
    }

    @Test
    fun apply_autoQueuePositionBottom_appendsToEndOfQueue() = runTest {
        feedRepository.subscribe(Feed(title = "Existing")).let { existingFeedId ->
            feedRepository.insertItems(
                listOf(
                    FeedItem(
                        id = "existing-ep",
                        feedId = existingFeedId,
                        title = "Existing Episode",
                        itemGuid = "existing-ep",
                        enclosureUrl = "https://example.com/existing-ep.mp3",
                        enclosureType = "audio/mpeg",
                    ),
                ),
            )
            queueRepository.addToEnd("existing-ep")
        }
        val feedId = subscribeAndInsertEpisode("new-ep", AutoQueuePosition.BOTTOM)

        enforcer.apply(listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = listOf("new-ep"), evictedItemIds = emptyList())))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("existing-ep", "new-ep"), queue.map { it.item.id })
    }

    @Test
    fun apply_autoQueuePositionTop_insertsAtFrontOfQueue() = runTest {
        feedRepository.subscribe(Feed(title = "Existing")).let { existingFeedId ->
            feedRepository.insertItems(
                listOf(
                    FeedItem(
                        id = "existing-ep",
                        feedId = existingFeedId,
                        title = "Existing Episode",
                        itemGuid = "existing-ep",
                        enclosureUrl = "https://example.com/existing-ep.mp3",
                        enclosureType = "audio/mpeg",
                    ),
                ),
            )
            queueRepository.addToEnd("existing-ep")
        }
        val feedId = subscribeAndInsertEpisode("new-ep", AutoQueuePosition.TOP)

        enforcer.apply(listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = listOf("new-ep"), evictedItemIds = emptyList())))

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("new-ep", "existing-ep"), queue.map { it.item.id })
    }

    @Test
    fun apply_autoQueuePositionTop_stillMarksEntryAsAutoQueuedForCapEviction() = runTest {
        val feedId = feedRepository.subscribe(
            Feed(title = "A Podcast", autoQueueEnabled = true, autoQueueMaxCount = 1, autoQueuePosition = AutoQueuePosition.TOP),
        )
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "ep-1", enclosureUrl = "https://example.com/1.mp3", enclosureType = "audio/mpeg"),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "ep-2", enclosureUrl = "https://example.com/2.mp3", enclosureType = "audio/mpeg"),
            ),
        )

        enforcer.apply(
            listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = listOf("ep-1", "ep-2"), evictedItemIds = emptyList())),
        )

        // Cap = 1: both got auto-queued to the front, then eviction trims back down to 1 -- the
        // eviction must still see these front-inserted entries as auto-queued candidates.
        val queue = queueRepository.observeQueue().first()
        assertEquals(1, queue.size)
        assertEquals(feedId, queue.single().item.feedId)
    }

    @Test
    fun apply_moreNewEpisodesThanCap_onlyQueuesNewestByPublishDate() = runTest {
        // issue #102's queue-side sibling bug: the old behavior added every one of newItemIds to
        // the queue (in whatever order FeedUpdateEngine happened to encounter them -- typically
        // newest-first for a real RSS feed) and only trimmed back down to autoQueueMaxCount
        // afterward, by oldest *addedAt* (insertion order). For a newest-first newItemIds order,
        // that evicted by insertion order rather than publish date -- keeping the *oldest*
        // episode and discarding the newest, backwards from what a listener would want, on top of
        // writing (and immediately deleting) rows for every candidate. newItemIds is passed here
        // in newest-first order (matching a typical RSS document) specifically to catch that.
        val feedId = feedRepository.subscribe(
            Feed(title = "A Podcast", autoQueueEnabled = true, autoQueueMaxCount = 1, autoQueuePosition = AutoQueuePosition.BOTTOM),
        )
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-old", feedId = feedId, itemGuid = "ep-old", enclosureUrl = "https://example.com/old.mp3", enclosureType = "audio/mpeg", publishDate = 1L),
                FeedItem(id = "ep-mid", feedId = feedId, itemGuid = "ep-mid", enclosureUrl = "https://example.com/mid.mp3", enclosureType = "audio/mpeg", publishDate = 2L),
                FeedItem(id = "ep-new", feedId = feedId, itemGuid = "ep-new", enclosureUrl = "https://example.com/new.mp3", enclosureType = "audio/mpeg", publishDate = 3L),
            ),
        )

        enforcer.apply(
            listOf(
                FeedUpdateResult.Success(
                    feedId = feedId,
                    newItemIds = listOf("ep-new", "ep-mid", "ep-old"),
                    evictedItemIds = emptyList(),
                ),
            ),
        )

        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-new"), queue.map { it.item.id })
    }

    @Test
    fun apply_moreNewEpisodesThanDownloadCap_onlyDownloadsNewestByPublishDate() = runTest {
        // Regression test for a real crash: the old behavior started a download for every one of
        // newItemIds and only trimmed back down to maxDownloadsToKeep afterward (once downloads
        // completed) -- a feed's first fetch bringing in hundreds/thousands of "new" episodes at
        // once, with auto-download enabled, enqueued that many concurrent EnclosureDownloadWorkers
        // and OOM-crashed the app. Mirrors apply_moreNewEpisodesThanCap_onlyQueuesNewestByPublishDate
        // above for the auto-queue side of this same bug class (issue #102).
        val enqueuedItemIds = mutableListOf<String>()
        val recordingDownloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
                    enqueuedItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val recordingEnforcer = AutoQueueAndDownloadEnforcer(feedRepository, recordingDownloadRepository, queueRepository)
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = 1))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-old", feedId = feedId, itemGuid = "ep-old", enclosureUrl = "https://example.com/old.mp3", enclosureType = "audio/mpeg", publishDate = 1L),
                FeedItem(id = "ep-mid", feedId = feedId, itemGuid = "ep-mid", enclosureUrl = "https://example.com/mid.mp3", enclosureType = "audio/mpeg", publishDate = 2L),
                FeedItem(id = "ep-new", feedId = feedId, itemGuid = "ep-new", enclosureUrl = "https://example.com/new.mp3", enclosureType = "audio/mpeg", publishDate = 3L),
            ),
        )

        recordingEnforcer.apply(
            listOf(
                FeedUpdateResult.Success(
                    feedId = feedId,
                    newItemIds = listOf("ep-new", "ep-mid", "ep-old"),
                    evictedItemIds = emptyList(),
                ),
            ),
        )

        assertEquals(listOf("ep-new"), enqueuedItemIds)
    }

    @Test
    fun apply_autoDownload_doesNotDownloadEpisodeOlderThanAlreadyDownloaded() = runTest {
        // issue #162: a "new" item that happens to be older than what's already downloaded for
        // this feed (a backfilled bonus episode, a reissued itemGuid) shouldn't get downloaded
        // just because there's cap headroom -- auto-download should only ever move forward in
        // time, never reach back into the catalog.
        val enqueuedItemIds = mutableListOf<String>()
        val recordingDownloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
                    enqueuedItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val recordingEnforcer = AutoQueueAndDownloadEnforcer(feedRepository, recordingDownloadRepository, queueRepository)
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = 3))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-already-downloaded", feedId = feedId, itemGuid = "ep-already-downloaded", enclosureUrl = "https://example.com/already.mp3", enclosureType = "audio/mpeg", publishDate = 10L),
                FeedItem(id = "ep-older-backfill", feedId = feedId, itemGuid = "ep-older-backfill", enclosureUrl = "https://example.com/older.mp3", enclosureType = "audio/mpeg", publishDate = 5L),
            ),
        )
        feedRepository.setAutoDownloaded("ep-already-downloaded", true)
        feedRepository.setDownloadedFilePath("ep-already-downloaded", "/fake/already.mp3")

        recordingEnforcer.apply(
            listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = listOf("ep-older-backfill"), evictedItemIds = emptyList())),
        )

        assertEquals(emptyList<String>(), enqueuedItemIds)
    }

    @Test
    fun apply_autoDownload_downloadsNewerEpisodeAndEvictsOldestWhenAtCap() = runTest {
        // issue #162: once the cap is already satisfied, a genuinely newer episode should still
        // be downloaded, expiring the current oldest auto-download to make room for it.
        val enqueuedItemIds = mutableListOf<String>()
        val recordingDownloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
                    enqueuedItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val recordingEnforcer = AutoQueueAndDownloadEnforcer(feedRepository, recordingDownloadRepository, queueRepository)
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = 1))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-old", feedId = feedId, itemGuid = "ep-old", enclosureUrl = "https://example.com/old.mp3", enclosureType = "audio/mpeg", publishDate = 10L),
                FeedItem(id = "ep-new", feedId = feedId, itemGuid = "ep-new", enclosureUrl = "https://example.com/new.mp3", enclosureType = "audio/mpeg", publishDate = 20L),
            ),
        )
        feedRepository.setAutoDownloaded("ep-old", true)
        feedRepository.setDownloadedFilePath("ep-old", "/fake/old.mp3")

        recordingEnforcer.apply(
            listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = listOf("ep-new"), evictedItemIds = emptyList())),
        )
        assertEquals(listOf("ep-new"), enqueuedItemIds)
        // Eviction only re-catches-up once a just-started download actually completes (see
        // EnclosureDownloadRepository.completeDownload) -- enforceFeedDownloadCap right after
        // starting it sees nothing new to trim yet, since ep-new hasn't finished downloading.
        recordingDownloadRepository.completeDownload("ep-new", "/fake/new.mp3")

        assertEquals(null, feedRepository.getItem("ep-old")?.downloadedFilePath)
    }

    @Test
    fun setMaxDownloadsToKeep_raisingCap_doesNotRetroactivelyDownloadOlderEpisodes() = runTest {
        // issue #162: raising maxDownloadsToKeep should only affect eviction/retention of future
        // episodes, not reach back and start downloading episodes that were already skipped.
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = 1))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-old", feedId = feedId, itemGuid = "ep-old", enclosureUrl = "https://example.com/old.mp3", enclosureType = "audio/mpeg", publishDate = 1L),
            ),
        )

        val feed = feedRepository.getFeed(feedId)!!
        feedRepository.updateFeed(feed.copy(maxDownloadsToKeep = 10))

        assertEquals(null, feedRepository.getItem("ep-old")?.downloadedFilePath)
    }

    @Test
    fun apply_autoDownload_unlimitedCap_stillBoundsSingleRefreshBurst() = runTest {
        // issue #172: a real incident showed a long-delayed refresh (the periodic job had been
        // stuck, issue #164) hitting a feed with an unlimited download cap and starting thousands
        // of downloads in one shot. maxDownloadsToKeep = null must still bound a single burst.
        val enqueuedItemIds = mutableListOf<String>()
        val recordingDownloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
                    enqueuedItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val recordingEnforcer = AutoQueueAndDownloadEnforcer(feedRepository, recordingDownloadRepository, queueRepository)
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = null))
        val itemIds = (1..30).map { "ep-$it" }
        feedRepository.insertItems(
            itemIds.mapIndexed { index, id ->
                FeedItem(id = id, feedId = feedId, itemGuid = id, enclosureUrl = "https://example.com/$id.mp3", enclosureType = "audio/mpeg", publishDate = index.toLong())
            },
        )

        recordingEnforcer.apply(
            listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = itemIds, evictedItemIds = emptyList())),
        )

        assertEquals(25, enqueuedItemIds.size)
    }

    @Test
    fun apply_autoQueue_unlimitedCap_stillBoundsSingleRefreshBurst() = runTest {
        // issue #172: same burst-bounding as the auto-download case above, for auto-queue -- queued
        // items are also permanently exempt from the episode-count trim, so an unbounded burst here
        // would make itself permanently un-trimmable too.
        val feedId = feedRepository.subscribe(
            Feed(title = "A Podcast", autoQueueEnabled = true, autoQueueMaxCount = null, autoQueuePosition = AutoQueuePosition.BOTTOM),
        )
        val itemIds = (1..30).map { "ep-$it" }
        feedRepository.insertItems(
            itemIds.mapIndexed { index, id ->
                FeedItem(id = id, feedId = feedId, itemGuid = id, enclosureUrl = "https://example.com/$id.mp3", enclosureType = "audio/mpeg", publishDate = index.toLong())
            },
        )

        enforcer.apply(
            listOf(FeedUpdateResult.Success(feedId = feedId, newItemIds = itemIds, evictedItemIds = emptyList())),
        )

        assertEquals(25, queueRepository.observeQueue().first().size)
    }

    @Test
    fun apply_autoDownload_unlimitedCap_doesNotDownloadEpisodeOlderThanAlreadyDownloaded() = runTest {
        // issue #172 follow-up: the "don't reach backward" protection from issue #162 only ever
        // computed newestAlreadyDownloaded when maxDownloadsToKeep was set -- an unlimited-cap feed
        // had no such protection at all, so a batch containing an older item (a backfilled bonus
        // episode, a reissued itemGuid, or a long-overdue refresh's backlog) would still download it.
        val enqueuedItemIds = mutableListOf<String>()
        val recordingDownloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
                    enqueuedItemIds += itemId
                }
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        val recordingEnforcer = AutoQueueAndDownloadEnforcer(feedRepository, recordingDownloadRepository, queueRepository)
        val feedId = feedRepository.subscribe(Feed(title = "A Podcast", autoDownloadEnabled = true, maxDownloadsToKeep = null))
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-already-downloaded", feedId = feedId, itemGuid = "ep-already-downloaded", enclosureUrl = "https://example.com/already.mp3", enclosureType = "audio/mpeg", publishDate = 10L),
                FeedItem(id = "ep-older-backfill", feedId = feedId, itemGuid = "ep-older-backfill", enclosureUrl = "https://example.com/older.mp3", enclosureType = "audio/mpeg", publishDate = 5L),
                FeedItem(id = "ep-genuinely-new", feedId = feedId, itemGuid = "ep-genuinely-new", enclosureUrl = "https://example.com/new.mp3", enclosureType = "audio/mpeg", publishDate = 20L),
            ),
        )
        feedRepository.setAutoDownloaded("ep-already-downloaded", true)
        feedRepository.setDownloadedFilePath("ep-already-downloaded", "/fake/already.mp3")

        recordingEnforcer.apply(
            listOf(
                FeedUpdateResult.Success(
                    feedId = feedId,
                    newItemIds = listOf("ep-older-backfill", "ep-genuinely-new"),
                    evictedItemIds = emptyList(),
                ),
            ),
        )

        assertEquals(listOf("ep-genuinely-new"), enqueuedItemIds)
    }
}
