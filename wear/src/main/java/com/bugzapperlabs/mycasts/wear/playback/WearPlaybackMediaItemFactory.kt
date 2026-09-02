package com.bugzapperlabs.mycasts.wear.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository

/** Everything the watch's player needs to start [FeedItem] (issue #276) -- trimmed from `:app`'s
 *  [com.bugzapperlabs.mycasts.playback.ResolvedPlaybackMedia]: no per-feed playback speed or
 *  volume boost, since those settings aren't part of the synced [com.bugzapperlabs.mycasts.data.local.Feed]
 *  snapshot [com.bugzapperlabs.mycasts.wear.sync.WearQueueSyncApplier] writes -- only its title/
 *  artwork are. */
data class WearResolvedPlaybackMedia(val mediaItem: MediaItem, val startPositionMs: Long)

/** Resolves a [FeedItem] into a playable Media3 item, mirroring `:app`'s
 *  [com.bugzapperlabs.mycasts.playback.PlaybackMediaItemFactory] but simplified: always streams
 *  (via [WearPlaybackUrlResolver]), no mobile-data gating (the watch has its own connection,
 *  phase 1 doesn't distinguish Wi-Fi/cellular for it), no skip-at-start/speed/volume-boost feed
 *  settings (issue #276). */
object WearPlaybackMediaItemFactory {
    suspend fun resolve(item: FeedItem, feedRepository: FeedRepository): WearResolvedPlaybackMedia? {
        val uri = WearPlaybackUrlResolver.resolve(item) ?: return null
        val feed = feedRepository.getFeed(item.feedId)
        val artworkUrl = item.imageUrl ?: feed?.imageUrl
        val startPositionMs = item.enclosurePosition?.let { (it * 1000).toLong() } ?: 0L

        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(feed?.userTitle ?: feed?.title)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .build(),
            )
            .build()

        return WearResolvedPlaybackMedia(mediaItem, startPositionMs)
    }
}
