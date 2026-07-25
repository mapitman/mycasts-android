package com.bugzapperlabs.myfeeds.playback

import com.bugzapperlabs.myfeeds.data.local.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackUrlResolverTest {
    private val item = FeedItem(
        id = "item-1",
        feedId = 1L,
        itemGuid = "g1",
        enclosureUrl = "https://example.com/episode.mp3",
    )

    @Test
    fun resolve_downloadedFile_preferredOverStreaming() {
        val uri = PlaybackUrlResolver.resolve(item, downloadedFilePath = "/data/episode.mp3", allowStreaming = false)

        assertEquals("/data/episode.mp3", uri)
    }

    @Test
    fun resolve_noDownload_streamingAllowed_returnsEnclosureUrl() {
        val uri = PlaybackUrlResolver.resolve(item, downloadedFilePath = null, allowStreaming = true)

        assertEquals("https://example.com/episode.mp3", uri)
    }

    @Test
    fun resolve_noDownload_streamingDisallowed_returnsNull() {
        val uri = PlaybackUrlResolver.resolve(item, downloadedFilePath = null, allowStreaming = false)

        assertNull(uri)
    }
}
