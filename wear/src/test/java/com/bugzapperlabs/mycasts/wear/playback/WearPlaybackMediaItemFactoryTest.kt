package com.bugzapperlabs.mycasts.wear.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearPlaybackMediaItemFactoryTest {
    private lateinit var db: AppDatabase
    private lateinit var feedRepository: FeedRepository
    private var feedId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        feedRepository = FeedRepository(db.feedDao(), db.feedItemDao(), db.queueDao())
        feedId = feedRepository.subscribe(Feed(title = "A Feed", imageUrl = "https://example.com/feed.png"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun resolve_buildsMediaItemFromEnclosureUrl() = runTest {
        val item = FeedItem(
            id = "ep-1", feedId = feedId, title = "Episode 1",
            enclosureUrl = "https://example.com/1.mp3",
        )

        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository)

        assertEquals("ep-1", resolved?.mediaItem?.mediaId)
        assertEquals("https://example.com/1.mp3", resolved?.mediaItem?.localConfiguration?.uri?.toString())
        assertEquals("Episode 1", resolved?.mediaItem?.mediaMetadata?.title?.toString())
    }

    @Test
    fun resolve_noEnclosureUrl_returnsNull() = runTest {
        val item = FeedItem(id = "ep-1", feedId = feedId, title = "Episode 1", enclosureUrl = null)

        assertNull(WearPlaybackMediaItemFactory.resolve(item, feedRepository))
    }

    @Test
    fun resolve_missingEpisodeArtwork_fallsBackToFeedArtwork() = runTest {
        val item = FeedItem(
            id = "ep-1", feedId = feedId, title = "Episode 1",
            enclosureUrl = "https://example.com/1.mp3", imageUrl = null,
        )

        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository)

        assertEquals("https://example.com/feed.png", resolved?.mediaItem?.mediaMetadata?.artworkUri?.toString())
    }

    @Test
    fun resolve_savedPosition_becomesStartPositionMs() = runTest {
        val item = FeedItem(
            id = "ep-1", feedId = feedId, title = "Episode 1",
            enclosureUrl = "https://example.com/1.mp3", enclosurePosition = 12.5,
        )

        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository)

        assertEquals(12_500L, resolved?.startPositionMs)
    }

    @Test
    fun resolve_noSavedPosition_startsFromZero() = runTest {
        val item = FeedItem(
            id = "ep-1", feedId = feedId, title = "Episode 1",
            enclosureUrl = "https://example.com/1.mp3", enclosurePosition = null,
        )

        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository)

        assertEquals(0L, resolved?.startPositionMs)
    }
}
