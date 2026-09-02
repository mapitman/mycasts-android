package com.bugzapperlabs.mycasts.sync

import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSyncMapperTest {
    @Test
    fun toSyncQueueItems_mapsFieldsAndPreservesOrder() {
        val episodes = listOf(
            QueuedEpisode(
                item = FeedItem(
                    id = "ep-1", feedId = 1L, title = "Episode One",
                    enclosureUrl = "https://example.com/1.mp3", imageUrl = "https://example.com/ep1.png",
                    enclosureDurationMs = 60_000L, enclosurePosition = 12.5,
                ),
                feedTitle = "A Feed",
                feedImageUrl = "https://example.com/feed.png",
            ),
            QueuedEpisode(
                item = FeedItem(id = "ep-2", feedId = 1L, title = "Episode Two"),
                feedTitle = "A Feed",
                feedImageUrl = "https://example.com/feed.png",
            ),
        )

        val result = episodes.toSyncQueueItems()

        assertEquals(
            listOf(
                SyncQueueItem(
                    itemId = "ep-1", feedId = 1L, title = "Episode One", feedTitle = "A Feed",
                    enclosureUrl = "https://example.com/1.mp3", artworkUrl = "https://example.com/ep1.png",
                    durationMs = 60_000L, positionMs = 12_500L, orderIndex = 0,
                ),
                SyncQueueItem(
                    itemId = "ep-2", feedId = 1L, title = "Episode Two", feedTitle = "A Feed",
                    enclosureUrl = null, artworkUrl = "https://example.com/feed.png",
                    durationMs = null, positionMs = null, orderIndex = 1,
                ),
            ),
            result,
        )
    }

    @Test
    fun toSyncQueueItems_missingEpisodeArtwork_fallsBackToFeedArtwork() {
        val episodes = listOf(
            QueuedEpisode(
                item = FeedItem(id = "ep-1", feedId = 1L, title = "Episode One", imageUrl = null),
                feedTitle = "A Feed",
                feedImageUrl = "https://example.com/feed.png",
            ),
        )

        val result = episodes.toSyncQueueItems()

        assertEquals("https://example.com/feed.png", result.single().artworkUrl)
    }

    @Test
    fun toSyncQueueItems_emptyQueue_returnsEmptyList() {
        assertEquals(emptyList<SyncQueueItem>(), emptyList<QueuedEpisode>().toSyncQueueItems())
    }
}
