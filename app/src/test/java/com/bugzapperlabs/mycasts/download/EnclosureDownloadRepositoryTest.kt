package com.bugzapperlabs.mycasts.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class EnclosureDownloadRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private val enqueuedCalls = mutableListOf<Triple<String, Boolean, Boolean>>()
    private val cancelledCalls = mutableListOf<String>()
    private var cancelAllCallCount = 0
    private var feedId: Long = 0

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
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {
                    enqueuedCalls += Triple(itemId, allowCellular, allowOnBattery)
                }

                override fun cancelDownload(itemId: String) {
                    cancelledCalls += itemId
                }

                override fun cancelAllDownloads() {
                    cancelAllCallCount++
                }

                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        feedId = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFeedAndItem(enclosureUrl: String?, enclosureType: String? = "audio/mpeg"): Long {
        val id = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "item-1",
                    feedId = id,
                    itemGuid = "g1",
                    enclosureUrl = enclosureUrl,
                    enclosureType = enclosureUrl?.let { enclosureType },
                ),
            ),
        )
        return id
    }

    @Test
    fun startDownload_passesCurrentSettingsToScheduler() = runTest {
        seedFeedAndItem("https://example.com/episode.mp3")
        settingsDataStore.setAllowPodcastDownloadOnCellular(true)
        settingsDataStore.setAllowPodcastDownloadOnBattery(false)
        val item = repository.getItem("item-1")!!

        downloadRepository.startDownload(item)

        assertEquals(listOf(Triple("item-1", true, false)), enqueuedCalls)
    }

    @Test
    fun startDownload_noEnclosureUrl_doesNothing() = runTest {
        seedFeedAndItem(enclosureUrl = null)
        val item = repository.getItem("item-1")!!

        downloadRepository.startDownload(item)

        assertTrue(enqueuedCalls.isEmpty())
    }

    @Test
    fun startDownload_nonAudioEnclosure_doesNothing() = runTest {
        // e.g. a featured-image enclosure on a plain article, not a podcast episode.
        seedFeedAndItem("https://example.com/cover.jpg", enclosureType = "image/jpeg")
        val item = repository.getItem("item-1")!!

        downloadRepository.startDownload(item)

        assertTrue(enqueuedCalls.isEmpty())
    }

    @Test
    fun deleteDownload_cancelsWorkDeletesFileAndClearsPath() = runTest {
        seedFeedAndItem("https://example.com/episode.mp3")
        val file = tempFolder.newFile("downloaded.mp3")
        repository.setDownloadedFilePath("item-1", file.absolutePath)
        val item = repository.getItem("item-1")!!

        downloadRepository.deleteDownload(item)

        assertEquals(listOf("item-1"), cancelledCalls)
        assertFalse(file.exists())
        assertNull(repository.getItem("item-1")?.downloadedFilePath)
    }

    @Test
    fun deleteDownload_inProgressDownload_clearsDownloadedBytesToo() = runTest {
        // issue #156: without also clearing downloadedBytes, a cancelled in-progress download
        // (no downloadedFilePath yet, but a partial byte count already persisted) would keep
        // showing as "in progress" forever -- isInProgress only checks downloadedFilePath.
        seedFeedAndItem("https://example.com/episode.mp3")
        repository.setDownloadedBytes("item-1", 512L)
        val item = repository.getItem("item-1")!!

        downloadRepository.deleteDownload(item)

        assertNull(repository.getItem("item-1")?.downloadedBytes)
    }

    @Test
    fun cancelAllDownloads_delegatesToScheduler() = runTest {
        downloadRepository.cancelAllDownloads()

        assertEquals(1, cancelAllCallCount)
    }

    @Test
    fun cancelAllDownloads_clearsDownloadedBytesForInProgressItems() = runTest {
        // issue #156: cancelling the WorkManager job alone leaves downloadedBytes untouched, so
        // an item that had already recorded some progress would otherwise keep showing as
        // "in progress" forever even after every job is cancelled.
        seedFeedAndItem("https://example.com/episode.mp3")
        repository.setDownloadedBytes("item-1", 512L)

        downloadRepository.cancelAllDownloads()

        assertNull(repository.getItem("item-1")?.downloadedBytes)
    }

    @Test
    fun cancelAllDownloads_leavesCompletedDownloadsAlone() = runTest {
        val file = tempFolder.newFile("downloaded.mp3")
        seedFeedAndItem("https://example.com/episode.mp3")
        repository.setDownloadedFilePath("item-1", file.absolutePath)

        downloadRepository.cancelAllDownloads()

        assertEquals(file.absolutePath, repository.getItem("item-1")?.downloadedFilePath)
    }

    @Test
    fun startDownload_marksItemAsAutoDownloadedWhenRequested() = runTest {
        seedFeedAndItem("https://example.com/episode.mp3")
        val item = repository.getItem("item-1")!!

        downloadRepository.startDownload(item, autoDownloaded = true)

        assertTrue(repository.getItem("item-1")!!.autoDownloaded)
    }

    /** Downloaded episodes of feed [feedId], newest [publishDate] first, each with its own file
     *  on disk so eviction assertions can check the underlying file was actually deleted. */
    private suspend fun seedDownloadedEpisodes(feedId: Long, count: Int, autoDownloaded: Boolean = true): List<File> {
        val items = (1..count).map { i ->
            FeedItem(
                id = "auto-$feedId-$i",
                feedId = feedId,
                itemGuid = "auto-$feedId-$i",
                enclosureUrl = "https://example.com/$i.mp3",
                enclosureType = "audio/mpeg",
                publishDate = i.toLong(),
            )
        }
        repository.insertItems(items)
        return items.map { item ->
            val file = tempFolder.newFile("${item.id}.mp3")
            repository.setDownloadedFilePath(item.id, file.absolutePath)
            repository.setAutoDownloaded(item.id, autoDownloaded)
            file
        }
    }

    @Test
    fun enforceFeedDownloadCap_deletesOldestExcessBeyondCap() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Podcast"))
        val files = seedDownloadedEpisodes(feedId, count = 3)

        downloadRepository.enforceFeedDownloadCap(feedId, maxCount = 1)

        // publishDate 1 and 2 are the oldest two of three -- evicted down to the newest one.
        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[2].exists())
        assertNull(repository.getItem("auto-$feedId-1")?.downloadedFilePath)
        assertNull(repository.getItem("auto-$feedId-2")?.downloadedFilePath)
        assertEquals(files[2].absolutePath, repository.getItem("auto-$feedId-3")?.downloadedFilePath)
    }

    @Test
    fun enforceFeedDownloadCap_underCap_deletesNothing() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Podcast"))
        val files = seedDownloadedEpisodes(feedId, count = 2)

        downloadRepository.enforceFeedDownloadCap(feedId, maxCount = 5)

        files.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun enforceFeedDownloadCap_exemptsQueuedEpisodeEvenIfOldest() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Podcast"))
        val files = seedDownloadedEpisodes(feedId, count = 2)
        db.queueDao().insert(QueueEntry(itemId = "auto-$feedId-1", position = 0, addedAt = 0))

        downloadRepository.enforceFeedDownloadCap(feedId, maxCount = 1)

        assertTrue(files[0].exists())
    }

    @Test
    fun enforceFeedDownloadCap_exemptsCurrentlyPlayingEpisodeEvenIfOldest() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Podcast"))
        val files = seedDownloadedEpisodes(feedId, count = 2)
        settingsDataStore.setLastPlayingItem(feedId, "auto-$feedId-1")

        downloadRepository.enforceFeedDownloadCap(feedId, maxCount = 1)

        assertTrue(files[0].exists())
    }

    @Test
    fun enforceFeedDownloadCap_neverEvictsManuallyDownloadedEpisode() = runTest {
        val feedId = repository.subscribe(Feed(title = "A Podcast"))
        val files = seedDownloadedEpisodes(feedId, count = 2, autoDownloaded = false)

        downloadRepository.enforceFeedDownloadCap(feedId, maxCount = 0)

        files.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun completeDownload_reEnforcesCapAsEachAutoDownloadLands() = runTest {
        // issue #102: a whole batch of a feed's new episodes gets enqueued for auto-download at
        // once (AutoQueueAndDownloadEnforcer.apply()), before any of them have actually finished
        // -- enforceFeedDownloadCap() only counts already-completed downloads, so calling it right
        // after enqueueing sees nothing to trim. completeDownload() re-checks the cap as each
        // download actually lands, so it catches up instead of leaving every enqueued download to
        // finish regardless of maxDownloadsToKeep.
        val feedId = repository.subscribe(Feed(title = "A Podcast", maxDownloadsToKeep = 1))
        val items = (1..3).map { i ->
            FeedItem(
                id = "ep-$i",
                feedId = feedId,
                itemGuid = "ep-$i",
                enclosureUrl = "https://example.com/$i.mp3",
                enclosureType = "audio/mpeg",
                publishDate = i.toLong(),
            )
        }
        repository.insertItems(items)
        items.forEach { repository.setAutoDownloaded(it.id, true) }
        val files = items.associate { it.id to tempFolder.newFile("${it.id}.mp3") }

        // All three "complete" in publish-date order, simulating downloads landing one at a time
        // while others are still enqueued/in-flight.
        items.forEach { downloadRepository.completeDownload(it.id, files.getValue(it.id).absolutePath) }

        // Only the newest (publishDate 3) survives -- each earlier one is evicted as soon as a
        // newer one completes and pushes it over the cap of 1.
        assertFalse(files.getValue("ep-1").exists())
        assertFalse(files.getValue("ep-2").exists())
        assertTrue(files.getValue("ep-3").exists())
    }

    @Test
    fun completeDownload_notAutoDownloaded_setsPathWithoutEnforcingCap() = runTest {
        // A manual download shouldn't trigger the auto-download cap at all -- mirrors
        // enforceFeedDownloadCap_neverEvictsManuallyDownloadedEpisode's own exemption.
        seedFeedAndItem("https://example.com/episode.mp3")
        val file = tempFolder.newFile("manual.mp3")

        downloadRepository.completeDownload("item-1", file.absolutePath)

        assertTrue(file.exists())
        assertEquals(file.absolutePath, repository.getItem("item-1")?.downloadedFilePath)
    }
}
