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
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class PlaybackMediaItemFactoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun resolve_streamingAllowed_buildsMediaItemFromFeedAndItem() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Feed", playbackSpeed = 1.5f))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
            enclosurePosition = 30.0,
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(item, "A Feed", feedRepository, settingsDataStore)

        requireNotNull(resolved)
        assertEquals("episode-1", resolved.mediaItem.mediaId)
        assertEquals("https://example.com/ep1.mp3", resolved.mediaItem.localConfiguration?.uri?.toString())
        assertEquals("Episode One", resolved.mediaItem.mediaMetadata.title?.toString())
        assertEquals("A Feed", resolved.mediaItem.mediaMetadata.artist?.toString())
        assertEquals(1.5f, resolved.speed)
        assertEquals(30_000L, resolved.startPositionMs)
    }

    @Test
    fun resolve_freshStart_skipsFeedsConfiguredStartSkip() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Feed", startSkipSeconds = 20))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
            enclosurePosition = null,
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(item, "A Feed", feedRepository, settingsDataStore)

        requireNotNull(resolved)
        assertEquals(20_000L, resolved.startPositionMs)
    }

    @Test
    fun resolve_hasSavedResumePosition_doesNotReapplyStartSkip() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Feed", startSkipSeconds = 20))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
            enclosurePosition = 5.0,
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(item, "A Feed", feedRepository, settingsDataStore)

        requireNotNull(resolved)
        assertEquals(5_000L, resolved.startPositionMs)
    }

    @Test
    fun resolve_carriesFeedsVolumeBoostAsMediaItemExtra() = runTest {
        val feedId = feedRepository.subscribe(Feed(title = "A Feed", volumeBoostMillibels = 1200))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(item, "A Feed", feedRepository, settingsDataStore)

        requireNotNull(resolved)
        assertEquals(1200, resolved.mediaItem.mediaMetadata.extras?.getInt(VOLUME_BOOST_EXTRA_KEY))
    }

    @Test
    fun resolve_onMobileDataAndNotAlwaysAllowedAndNotDownloaded_returnsNull() = runTest {
        // issue #222: alwaysAllowPodcastStreamingOnMobileData defaults to false -- this needs an
        // actually-cellular NetworkTypeChecker to trigger, since Wi-Fi is always allowed.
        val feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(
            item, "A Feed", feedRepository, settingsDataStore, networkTypeChecker = NetworkTypeChecker { true },
        )

        assertNull(resolved)
    }

    @Test
    fun resolve_notAlwaysAllowedButOnWifi_stillStreams() = runTest {
        // issue #123/#222: Wi-Fi streaming is always allowed regardless of the mobile-data setting.
        val feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(
            item, "A Feed", feedRepository, settingsDataStore, networkTypeChecker = NetworkTypeChecker { false },
        )

        assertNotNull(resolved)
    }

    @Test
    fun resolve_onMobileDataWithAlwaysAllowSet_stillStreams() = runTest {
        settingsDataStore.setAlwaysAllowPodcastStreamingOnMobileData(true)
        val feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(
            item, "A Feed", feedRepository, settingsDataStore, networkTypeChecker = NetworkTypeChecker { true },
        )

        assertNotNull(resolved)
    }

    @Test
    fun resolve_onMobileDataWithForceAllowStreaming_stillStreams() = runTest {
        // issue #222: forceAllowStreaming is how PlaybackController plays after the user
        // confirms the mobile-data warning for one specific episode, without persisting anything.
        val feedId = feedRepository.subscribe(Feed(title = "A Feed"))
        val item = FeedItem(
            id = "episode-1",
            feedId = feedId,
            title = "Episode One",
            itemGuid = "g1",
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
        )
        feedRepository.insertItems(listOf(item))

        val resolved = PlaybackMediaItemFactory.resolve(
            item, "A Feed", feedRepository, settingsDataStore,
            networkTypeChecker = NetworkTypeChecker { true }, forceAllowStreaming = true,
        )

        assertNotNull(resolved)
    }

    @Test
    fun needsMobileDataConfirmation_notDownloadedAndOnMobileDataAndNotAlwaysAllowed_returnsTrue() = runTest {
        val item = FeedItem(id = "episode-1", feedId = 1, itemGuid = "g1", enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg")

        val needsConfirmation = PlaybackMediaItemFactory.needsMobileDataConfirmation(
            item, settingsDataStore, networkTypeChecker = NetworkTypeChecker { true },
        )

        assertEquals(true, needsConfirmation)
    }

    @Test
    fun needsMobileDataConfirmation_onWifi_returnsFalse() = runTest {
        val item = FeedItem(id = "episode-1", feedId = 1, itemGuid = "g1", enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg")

        val needsConfirmation = PlaybackMediaItemFactory.needsMobileDataConfirmation(
            item, settingsDataStore, networkTypeChecker = NetworkTypeChecker { false },
        )

        assertEquals(false, needsConfirmation)
    }

    @Test
    fun needsMobileDataConfirmation_alwaysAllowSet_returnsFalse() = runTest {
        settingsDataStore.setAlwaysAllowPodcastStreamingOnMobileData(true)
        val item = FeedItem(id = "episode-1", feedId = 1, itemGuid = "g1", enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg")

        val needsConfirmation = PlaybackMediaItemFactory.needsMobileDataConfirmation(
            item, settingsDataStore, networkTypeChecker = NetworkTypeChecker { true },
        )

        assertEquals(false, needsConfirmation)
    }

    @Test
    fun needsMobileDataConfirmation_alreadyDownloaded_returnsFalse() = runTest {
        val item = FeedItem(
            id = "episode-1", feedId = 1, itemGuid = "g1", enclosureUrl = "https://example.com/ep1.mp3", enclosureType = "audio/mpeg",
            downloadedFilePath = tempFolder.newFile("ep1.mp3").absolutePath,
        )

        val needsConfirmation = PlaybackMediaItemFactory.needsMobileDataConfirmation(
            item, settingsDataStore, networkTypeChecker = NetworkTypeChecker { true },
        )

        assertEquals(false, needsConfirmation)
    }
}
