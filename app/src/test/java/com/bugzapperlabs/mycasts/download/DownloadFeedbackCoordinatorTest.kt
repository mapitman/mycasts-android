package com.bugzapperlabs.mycasts.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.bugzapperlabs.mycasts.download.DownloadWorkInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * [DownloadFeedbackCoordinator] runs on its own real (non-test-scheduler) coroutine scope, same as
 * [com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator] -- assertions here wait on its
 * exposed flows via real suspension ([first]) rather than virtual-time advancement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadFeedbackCoordinatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repository: FeedRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var coordinator: DownloadFeedbackCoordinator
    private val failureReason = MutableStateFlow<String?>(null)
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
                override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = failureReason
            },
            settingsDataStore = settingsDataStore,
        )
        coordinator = DownloadFeedbackCoordinator(
            downloadRepository = downloadRepository,
            feedRepository = repository,
            context = context,
        )
    }

    @After
    fun tearDown() = runTest {
        coordinator.cancelForTest()
        db.close()
    }

    private suspend fun seedItem(): FeedItem {
        feedId = repository.subscribe(Feed(title = "A Feed"))
        repository.insertItems(
            listOf(
                FeedItem(
                    id = "item-1",
                    feedId = feedId,
                    title = "First Episode",
                    itemGuid = "g1",
                    enclosureUrl = "https://example.com/episode.mp3",
                    enclosureType = "audio/mpeg",
                ),
            ),
        )
        return repository.getItem("item-1")!!
    }

    @Test
    fun startDownload_marksPendingImmediately() = runTest {
        val item = seedItem()

        coordinator.startDownload(item)

        assertTrue(item.id in coordinator.pendingItemIds.value)
    }

    @Test
    fun startDownload_clearsPendingOnceRealProgressAppears() = runTest {
        val item = seedItem()
        coordinator.downloadStartTimeoutMs = 5_000L

        coordinator.startDownload(item)
        // Simulates the worker's own first progress write, same as EnclosureDownloadWorker does.
        repository.setDownloadedBytes(item.id, 1_024L)

        val pending = coordinator.pendingItemIds.first { item.id !in it }
        assertTrue(item.id !in pending)
        assertNull(coordinator.result.value)
    }

    @Test
    fun startDownload_noProgressWithinTimeout_setsResultAndClearsPending() = runTest {
        val item = seedItem()
        coordinator.downloadStartTimeoutMs = 50L

        coordinator.startDownload(item)

        val result = coordinator.result.first { it != null }
        assertTrue(result!!.contains(item.title.orEmpty()))
        assertTrue(item.id !in coordinator.pendingItemIds.value)
    }

    @Test
    fun startDownload_lowStorageFailure_setsSpecificMessageBeforeTimeout() = runTest {
        // issue #209: a low-storage failure resolves almost immediately -- this uses a much
        // longer timeout than the failure signal's own near-instant arrival to prove the specific
        // message doesn't wait out the generic "didn't start" timeout at all.
        val item = seedItem()
        coordinator.downloadStartTimeoutMs = 5_000L

        coordinator.startDownload(item)
        failureReason.value = EnclosureDownloadWorker.FAILURE_REASON_LOW_STORAGE

        val result = coordinator.result.first { it != null }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(result!!.contains(item.title.orEmpty()))
        assertTrue(result != context.getString(R.string.download_feedback_not_started, item.title.orEmpty()))
        assertEquals(context.getString(R.string.download_feedback_failed_low_storage, item.title.orEmpty()), result)
        assertTrue(item.id !in coordinator.pendingItemIds.value)
    }

    @Test
    fun consumeResult_clearsResult() = runTest {
        val item = seedItem()
        coordinator.downloadStartTimeoutMs = 50L
        coordinator.startDownload(item)
        coordinator.result.first { it != null }

        coordinator.consumeResult()

        assertEquals(null, coordinator.result.value)
    }
}
