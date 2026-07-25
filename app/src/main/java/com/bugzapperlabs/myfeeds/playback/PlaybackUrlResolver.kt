package com.bugzapperlabs.myfeeds.playback

import com.bugzapperlabs.myfeeds.data.local.FeedItem

/**
 * Ported rule from MyFeeds.AudioPlaybackAgent EnclosureControl.xaml.cs GetAudioTrack: a fully
 * downloaded episode plays from local storage regardless of the streaming setting; anything else
 * falls back to the remote enclosure URL, gated by [allowStreaming]. Local-file download tracking
 * doesn't exist yet in this port (issue #23), so [downloadedFilePath] is always null today -- this
 * resolver already supports it so the playback service doesn't need to change when #23 lands.
 */
object PlaybackUrlResolver {
    fun resolve(item: FeedItem, downloadedFilePath: String?, allowStreaming: Boolean): String? {
        if (downloadedFilePath != null) return downloadedFilePath
        if (!allowStreaming) return null
        return item.enclosureUrl
    }
}
