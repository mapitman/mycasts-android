package com.bugzapperlabs.mycasts.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.io.File

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnclosureDownloadWorkerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var downloadRepository: EnclosureDownloadRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var server: MockWebServer
    private var feedId: Long = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        downloadRepository = EnclosureDownloadRepository(
            feedRepository = feedRepository,
            downloadScheduling = object : DownloadScheduling {
                override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {}
                override fun cancelDownload(itemId: String) {}
                override fun cancelAllDownloads() {}
                override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = emptyFlow()
                override fun observeFailureReason(itemId: String): Flow<String?> = emptyFlow()
            },
            settingsDataStore = settingsDataStore,
        )
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun seedItem(url: String): FeedItem {
        feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        val item = FeedItem(
            id = "item-1",
            feedId = feedId,
            itemGuid = "g1",
            enclosureUrl = url,
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))
        return item
    }

    private fun buildWorker(context: Context) =
        TestListenableWorkerBuilder<EnclosureDownloadWorker>(context)
            .setInputData(workDataOf(EnclosureDownloadWorker.KEY_ITEM_ID to "item-1"))
            .setWorkerFactory(TestWorkerFactory(feedRepository, downloadRepository, OkHttpClient()))
            .build()

    @Test
    fun doWork_success_completesDownloadAndSetsFilePath() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("fake mp3 bytes"))
        server.start()
        seedItem(server.url("/episode.mp3").toString())
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = buildWorker(context).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("fake mp3 bytes", File(feedRepository.getItem("item-1")!!.downloadedFilePath!!).readText())
    }

    @Test
    fun doWork_serverError_retries() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        seedItem(server.url("/episode.mp3").toString())
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = buildWorker(context).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull(feedRepository.getItem("item-1")?.downloadedFilePath)
    }

    @Test
    fun doWork_connectionRefused_retries() = runTest {
        // issue #209: a plain dropped-connection IOException, distinct from the low-disk-space
        // case, still retries rather than giving up outright -- only a persistently low
        // downloadDir.usableSpace should ever turn an IOException into a permanent failure, and
        // this test environment's real disk has plenty of room.
        val item = seedItem("http://localhost:1/unreachable.mp3")
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = buildWorker(context).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull(feedRepository.getItem(item.id)?.downloadedFilePath)
    }

    private class TestWorkerFactory(
        private val feedRepository: FeedRepository,
        private val downloadRepository: EnclosureDownloadRepository,
        private val httpClient: OkHttpClient,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ) = EnclosureDownloadWorker(appContext, workerParameters, feedRepository, downloadRepository, httpClient)
    }
}
