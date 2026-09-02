package com.bugzapperlabs.mycasts.wear.playback

import com.bugzapperlabs.mycasts.data.local.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression guard (issue #276): the watch streams only in phase 1 -- a downloaded file on the
 *  watch is meaningless until phase 2 (issue #277) adds sending one there, so this must never
 *  start preferring [FeedItem.downloadedFilePath] the way `:app`'s
 *  [com.bugzapperlabs.mycasts.playback.PlaybackUrlResolver] does. */
class WearPlaybackUrlResolverTest {
    @Test
    fun resolve_returnsEnclosureUrl() {
        val item = FeedItem(id = "ep-1", feedId = 1L, enclosureUrl = "https://example.com/1.mp3")

        assertEquals("https://example.com/1.mp3", WearPlaybackUrlResolver.resolve(item))
    }

    @Test
    fun resolve_noEnclosureUrl_returnsNull() {
        val item = FeedItem(id = "ep-1", feedId = 1L, enclosureUrl = null)

        assertNull(WearPlaybackUrlResolver.resolve(item))
    }

    @Test
    fun resolve_ignoresDownloadedFilePath() {
        val item = FeedItem(
            id = "ep-1", feedId = 1L,
            enclosureUrl = "https://example.com/1.mp3",
            downloadedFilePath = "/local/already-downloaded.mp3",
        )

        assertEquals("https://example.com/1.mp3", WearPlaybackUrlResolver.resolve(item))
    }
}
