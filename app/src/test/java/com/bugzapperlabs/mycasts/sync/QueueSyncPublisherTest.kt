package com.bugzapperlabs.mycasts.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueDownloadTrigger
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
 * Exercises [QueueSyncPublisher.collectAndPush] directly against a fully virtual-time-controlled
 * [MutableSharedFlow] (issue #276), rather than through [QueueSyncPublisher.run] /
 * [QueueRepository.observeQueue] -- Room's Flow re-queries happen on a real (non-test) dispatcher,
 * which makes asserting exact debounce timing against it under `runTest` unreliable. The
 * queue-to-[SyncQueueItem] mapping itself is covered separately by `QueueSyncMapperTest` in
 * `:core`. [queueRepository] below only exists to satisfy [QueueSyncPublisher]'s constructor --
 * these tests never call anything on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueSyncPublisherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var queueRepository: QueueRepository
    private lateinit var syncClient: FakeWearSyncClient
    private lateinit var publisher: QueueSyncPublisher

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val noopTrigger = object : QueueDownloadTrigger {
            override suspend fun ensureDownloaded(item: FeedItem) {}
        }
        queueRepository = QueueRepository(db.queueDao(), feedRepository, noopTrigger, SettingsDataStore(dataStore))
        syncClient = FakeWearSyncClient()
        publisher = QueueSyncPublisher(queueRepository, syncClient)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun queuedEpisode(itemId: String) = QueuedEpisode(
        item = FeedItem(id = itemId, feedId = 1L, title = "Episode $itemId", itemGuid = itemId),
        feedTitle = "A Feed",
        feedImageUrl = null,
    )

    @Test
    fun collectAndPush_singleEmission_pushesAfterDebounce() = runTest {
        val source = MutableSharedFlow<List<QueuedEpisode>>()
        val job = launch { publisher.collectAndPush(source) }
        runCurrent()

        source.emit(listOf(queuedEpisode("ep-1")))
        advanceTimeBy(600)

        assertEquals(1, syncClient.sentQueueSnapshots.size)
        assertEquals(listOf("ep-1"), syncClient.sentQueueSnapshots.single().map { it.itemId })
        job.cancel()
    }

    @Test
    fun collectAndPush_rapidEmissions_coalescedIntoOnePush() = runTest {
        val source = MutableSharedFlow<List<QueuedEpisode>>()
        val job = launch { publisher.collectAndPush(source) }
        runCurrent()

        // Three emissions in immediate succession (no virtual time advances between them), well
        // inside the debounce window -- only the last one should actually reach the sync client.
        source.emit(listOf(queuedEpisode("ep-1")))
        source.emit(listOf(queuedEpisode("ep-1"), queuedEpisode("ep-2")))
        source.emit(listOf(queuedEpisode("ep-1"), queuedEpisode("ep-2"), queuedEpisode("ep-3")))
        advanceTimeBy(600)

        assertEquals(1, syncClient.sentQueueSnapshots.size)
        assertEquals(listOf("ep-1", "ep-2", "ep-3"), syncClient.sentQueueSnapshots.single().map { it.itemId })
        job.cancel()
    }

    @Test
    fun collectAndPush_failedPush_doesNotStopFutureEmissions() = runTest {
        var callCount = 0
        val failingClient = object : WearSyncClient by syncClient {
            override suspend fun putQueueSnapshot(queue: List<SyncQueueItem>) {
                callCount++
                if (callCount == 1) throw RuntimeException("watch unreachable")
                syncClient.putQueueSnapshot(queue)
            }
        }
        val failingPublisher = QueueSyncPublisher(queueRepository, failingClient)
        val source = MutableSharedFlow<List<QueuedEpisode>>()
        val job = launch { failingPublisher.collectAndPush(source) }
        runCurrent()

        source.emit(listOf(queuedEpisode("ep-1")))
        advanceTimeBy(600)
        source.emit(listOf(queuedEpisode("ep-1"), queuedEpisode("ep-2")))
        advanceTimeBy(600)

        // The first (failed) push isn't retried, but the collector keeps running -- the second
        // emission still reaches the client.
        assertEquals(listOf("ep-1", "ep-2"), syncClient.sentQueueSnapshots.single().map { it.itemId })
        job.cancel()
    }
}
