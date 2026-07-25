package com.bugzapperlabs.myfeeds.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var playbackController: PlaybackController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        queueRepository = QueueRepository(db.queueDao())
        playbackController = PlaybackController(
            context,
            SettingsDataStore(dataStore),
            feedRepository,
            queueRepository,
            ChaptersFetcher(OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        // Drains the controller's Main-bound scope so nothing leaked from this class can touch
        // a TestMainDispatcher a later test class installs -- see TrackedViewModelStore's doc
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
     * issue #171: the currently playing episode is already shown pinned to the top of the Next Up
     * screen via the current-playback player bar, so a leftover Next Up queue entry for it would
     * just be a duplicate -- playing an episode that's queued should dequeue it.
     */
    @Test
    fun play_episodeAlreadyQueued_removesItFromQueue() = runTest {
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
        queueRepository.addToEnd(item.id)

        playbackController.play(item, "Feed")

        assertFalse(queueRepository.isQueued(item.id))
    }
}
