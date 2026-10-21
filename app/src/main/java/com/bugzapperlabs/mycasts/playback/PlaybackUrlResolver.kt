package com.bugzapperlabs.mycasts.playback

import com.bugzapperlabs.mycasts.data.local.FeedItem

/**
 * Ported rule from MyFeeds.AudioPlaybackAgent EnclosureControl.xaml.cs GetAudioTrack: a fully
 * downloaded episode plays from local storage regardless of the streaming setting; anything else
 * falls back to the remote enclosure URL, gated by [allowStreaming]. [downloadedFilePath] is the
 * item's [FeedItem.downloadedFilePath], set by the download subsystem (issue #23).
 */
object PlaybackUrlResolver {
    fun resolve(item: FeedItem, downloadedFilePath: String?, allowStreaming: Boolean): String? {
        if (downloadedFilePath != null) return downloadedFilePath
        if (!allowStreaming) return null
        return item.enclosureUrl
    }
}
