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
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
 * Covers [AutoQueueAndDownloadEnforcer]'s auto-queue behavior -- in particular issue #166's
 * per-feed choice of adding newly-fetched episodes to the top vs. the bottom of the Next Up
 * queue. Downloading is no longer triggered from the enforcer (issue #219): it's triggered by
 * [QueueRepository] itself whenever an episode is added to Next Up, so that behavior is covered
 * by [com.bugzapperlabs.mycasts.data.repository.QueueRepositoryTest] instead.
 * [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorkerTest] already covers the bottom-of-queue
 * (default) path end-to-end via a real HTTP fetch; these tests construct [FeedUpdateResult.Success]
 * directly instead, since the position-choice logic lives entirely in this class and doesn't need
 * a real feed fetch/parse round trip to exercise.
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
        queueRepository = QueueRepository(db.queueDao(), feedRepository, downloadRepository, settingsDataStore)
        enforcer = AutoQueueAndDownloadEnforcer(feedRepository, queueRepository)
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
    fun apply_autoQueue_unlimitedCap_stillBoundsSingleRefreshBurst() = runTest {
        // issue #172: queued items are permanently exempt from the episode-count trim, so an
        // unbounded burst here would make itself permanently un-trimmable too.
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
}
