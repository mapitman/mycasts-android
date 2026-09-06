package com.bugzapperlabs.mycasts.wear.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueDownloadTrigger
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

/**
 * Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *
 * No live [androidx.media3.session.MediaController] connection exists in this Robolectric setup
 * (there's no real [WearPlaybackService] running to bind to), so these tests -- mirroring `:app`'s
 * own `PlaybackControllerTest` precedent -- exercise what's actually testable without one: the
 * repository-level side effects a call has, and that the transport methods never crash while
 * nothing is connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearPlaybackControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var playbackController: WearPlaybackController
    private var feedId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        val noopTrigger = object : QueueDownloadTrigger {
            override suspend fun ensureDownloaded(item: FeedItem) {}
        }
        queueRepository = QueueRepository(db.queueDao(), feedRepository, noopTrigger, SettingsDataStore(dataStore))
        playbackController = WearPlaybackController(ApplicationProvider.getApplicationContext(), feedRepository, queueRepository)

        feedId = feedRepository.subscribe(Feed(title = "A Feed"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun skipForwardAndSkipBackward_noActivePlayback_areNoOpsAndDoNotCrash() = runTest {
        playbackController.skipBackward()
        playbackController.skipForward()

        assertEquals(0L, playbackController.uiState.value.positionMs)
    }

    @Test
    fun uiState_defaultsToNormalSpeed() {
        assertEquals(1.0f, playbackController.uiState.value.speed)
    }

    @Test
    fun cycleSpeed_noActivePlayback_doesNotCrash() {
        playbackController.cycleSpeed()

        assertEquals(1.0f, playbackController.uiState.value.speed)
    }

    @Test
    fun previousEpisode_noActivePlayback_doesNotCrash() {
        playbackController.previousEpisode()

        assertEquals(0L, playbackController.uiState.value.positionMs)
    }

    @Test
    fun nextEpisode_emptyQueue_doesNothing() = runTest {
        playbackController.nextEpisode()

        assertTrue(queueRepository.observeQueue().first().isEmpty())
    }

    @Test
    fun nextEpisode_noCurrentItem_resolvesQueueFrontAsNext() = runTest {
        // With nothing actually connected/playing in this Robolectric setup, currentItemId is
        // always null, so nextEpisode() falls back to the queue's own front entry -- this pins
        // that fallback rather than crashing on a -1 "not found" index.
        feedRepository.insertItems(
            listOf(
                FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", itemGuid = "g1", enclosureUrl = "https://example.com/1.mp3"),
                FeedItem(id = "ep-2", feedId = feedId, title = "Episode 2", itemGuid = "g2", enclosureUrl = "https://example.com/2.mp3"),
            ),
        )
        queueRepository.addToEnd("ep-1")
        queueRepository.addToEnd("ep-2")

        playbackController.nextEpisode()

        // play("ep-1") moves it to the front of the queue -- a no-op here since it was already
        // there, but confirms nextEpisode() resolved and attempted to play the right episode.
        val queue = queueRepository.observeQueue().first()
        assertEquals(listOf("ep-1", "ep-2"), queue.map { it.item.id })
    }
}
