package com.bugzapperlabs.mycasts.playback

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PlaybackControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var context: android.content.Context
    private lateinit var playbackController: PlaybackController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        settingsDataStore = SettingsDataStore(dataStore)
        val downloadRepository = EnclosureDownloadRepository(
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
        playbackController = PlaybackController(
            context,
            settingsDataStore,
            feedRepository,
            queueRepository,
            ChaptersFetcher(OkHttpClient()),
            NetworkTypeChecker { false },
        )
    }

    @After
    fun tearDown() {
        // Drains the controller's Main-bound scope so nothing leaked from this class can touch
        // a TestMainDispatcher a later test class installs -- see ViewModelTestEnvironment's doc
        // for the leak mechanics (issues #54/#60).
        runTest { playbackController.awaitShutdownForTest() }
        db.close()
    }

    @Test
    fun skipForwardAndSkipBackward_noActivePlayback_areNoOpsAndDoNotCrash() = runTest {
        playbackController.skipBackward()
        playbackController.skipForward()

        assertEquals(0L, playbackController.uiState.value.positionMs)
    }

    @Test
    fun uiState_defaultsToNormalSpeed() = runTest {
        assertEquals(1.0f, playbackController.uiState.value.speed)
    }

    @Test
    fun setSpeed_noActivePlayback_doesNotCrash() = runTest {
        playbackController.setSpeed(1.5f)

        assertEquals(1.0f, playbackController.uiState.value.speed)
    }

    @Test
    fun uiState_defaultsToNoVolumeBoost() = runTest {
        assertEquals(0, playbackController.uiState.value.volumeBoostMillibels)
    }

    /** Issue #202: with no [androidx.media3.session.MediaController] connected (no active
     *  playback in this Robolectric setup), the optimistic UI update still applies even though the
     *  custom session command and feed persistence are skipped/no-ops. */
    @Test
    fun setVolumeBoost_noActivePlayback_updatesUiStateOptimisticallyAndDoesNotCrash() = runTest {
        playbackController.setVolumeBoost(1200)

        assertEquals(1200, playbackController.uiState.value.volumeBoostMillibels)
    }

    /**
     * issue #196: the currently-playing episode is a real Next Up queue entry itself -- always
     * the front one, clearly marked as playing -- rather than hidden from the queue entirely, so
     * playing an already-queued episode should move it to the front, not dequeue it.
     */
    @Test
    fun play_episodeAlreadyQueued_movesItToFrontOfQueue() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g-episode-1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        val otherItem = FeedItem(
            id = "episode-2",
            feedId = feedId,
            title = "Episode Two",
            itemGuid = "g-episode-2",
            enclosureUrl = "https://example.com/ep2.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item, otherItem))
        queueRepository.addToEnd(otherItem.id)
        queueRepository.addToEnd(item.id)

        playbackController.play(item, "Feed")

        assertTrue(queueRepository.isQueued(item.id))
        assertEquals(listOf(item.id, otherItem.id), queueRepository.observeQueue().first().map { it.item.id })
    }

    /**
     * issue #196: playing an episode that wasn't queued at all should insert it at the front, the
     * same as moving an already-queued one there.
     */
    @Test
    fun play_episodeNotQueued_insertsItAtFrontOfQueue() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g-episode-1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        playbackController.play(item, "Feed")

        assertTrue(queueRepository.isQueued(item.id))
    }

    /** Issue #287: switching away from an episode to play a different one must persist the
     *  outgoing episode's position rather than leaving it for [PlaybackService]'s own save loop --
     *  that loop only ever acts on `player.currentMediaItem`, which by the time either its
     *  periodic tick or its on-pause save fires typically already reflects the *new* episode, so
     *  without this the outgoing episode's progress was silently lost. */
    @Test
    fun play_switchingToADifferentEpisode_persistsOutgoingEpisodesPosition() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val outgoing = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
            enclosurePosition = 987.0,
        )
        val incoming = FeedItem(
            id = "episode-2", feedId = feedId, itemGuid = "g2",
            enclosureUrl = "https://example.com/ep2.mp3", enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(outgoing, incoming))
        playbackController.play(outgoing, "Feed")

        playbackController.play(incoming, "Feed")

        // No real MediaController is connected in this Robolectric setup, so the tracked
        // positionMs a real switch would persist is 0 here -- what matters is that a write for the
        // outgoing episode happens at all (overwriting its earlier stale value) rather than never
        // happening, which is what issue #287 was about.
        assertEquals(0.0, feedRepository.getItem(outgoing.id)?.enclosurePosition)
        playbackController.awaitShutdownForTest()
    }

    /** Playing the same episode that's already current is not a "switch", so it must not overwrite
     *  that episode's own just-loaded position with whatever happened to be tracked before it. */
    @Test
    fun play_sameEpisodeAlreadyPlaying_doesNotOverwriteItsOwnPosition() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
            enclosurePosition = 987.0,
        )
        feedRepository.insertItems(listOf(item))
        playbackController.play(item, "Feed")
        feedRepository.setEnclosurePosition(item.id, 42.0)

        playbackController.play(item, "Feed")

        assertEquals(42.0, feedRepository.getItem(item.id)?.enclosurePosition)
        playbackController.awaitShutdownForTest()
    }

    private fun newController(networkTypeChecker: NetworkTypeChecker) = PlaybackController(
        context,
        settingsDataStore,
        feedRepository,
        queueRepository,
        ChaptersFetcher(OkHttpClient()),
        networkTypeChecker,
    )

    /** issue #222: playing an undownloaded episode over mobile data (without "always allow" set)
     *  surfaces a pending confirmation instead of silently doing nothing, and doesn't queue/play
     *  the episode yet. */
    @Test
    fun play_notDownloadedOnMobileDataWithoutAlwaysAllow_surfacesPendingConfirmationInsteadOfPlaying() = runTest {
        val cellularController = newController(NetworkTypeChecker { true })
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val started = cellularController.play(item, "Feed")

        assertFalse(started)
        assertFalse(queueRepository.isQueued(item.id))
        assertEquals(item.id, cellularController.pendingMobileDataConfirmation.value?.item?.id)
        cellularController.awaitShutdownForTest()
    }

    @Test
    fun confirmPendingMobileDataStreaming_playsAndClearsThePendingState() = runTest {
        val cellularController = newController(NetworkTypeChecker { true })
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))
        cellularController.play(item, "Feed")

        cellularController.confirmPendingMobileDataStreaming(alwaysAllow = false)

        assertTrue(queueRepository.isQueued(item.id))
        assertEquals(null, cellularController.pendingMobileDataConfirmation.value)
        assertFalse(settingsDataStore.settings.first().alwaysAllowPodcastStreamingOnMobileData)
        cellularController.awaitShutdownForTest()
    }

    @Test
    fun confirmPendingMobileDataStreaming_alwaysAllow_persistsTheSetting() = runTest {
        val cellularController = newController(NetworkTypeChecker { true })
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))
        cellularController.play(item, "Feed")

        cellularController.confirmPendingMobileDataStreaming(alwaysAllow = true)

        assertTrue(settingsDataStore.settings.first().alwaysAllowPodcastStreamingOnMobileData)
        cellularController.awaitShutdownForTest()
    }

    @Test
    fun dismissPendingMobileDataConfirmation_clearsStateWithoutPlaying() = runTest {
        val cellularController = newController(NetworkTypeChecker { true })
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))
        cellularController.play(item, "Feed")

        cellularController.dismissPendingMobileDataConfirmation()

        assertEquals(null, cellularController.pendingMobileDataConfirmation.value)
        assertFalse(queueRepository.isQueued(item.id))
        cellularController.awaitShutdownForTest()
    }

    @Test
    fun play_alreadyDownloadedOnMobileData_playsWithoutConfirmation() = runTest {
        val cellularController = newController(NetworkTypeChecker { true })
        val feedId = feedRepository.subscribe(Feed(title = "Feed"))
        val item = FeedItem(
            id = "episode-1", feedId = feedId, itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
            downloadedFilePath = tempFolder.newFile("ep1.mp3").absolutePath,
        )
        feedRepository.insertItems(listOf(item))

        cellularController.play(item, "Feed")

        assertTrue(queueRepository.isQueued(item.id))
        assertEquals(null, cellularController.pendingMobileDataConfirmation.value)
        cellularController.awaitShutdownForTest()
    }
}
