package com.bugzapperlabs.myfeeds.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.io.File

/** Everything a Media3 player needs to start [FeedItem], resolved outside the player itself. */
data class ResolvedPlaybackMedia(
    val mediaItem: MediaItem,
    val speed: Float,
    val startPositionMs: Long,
    val volumeBoostMillibels: Int,
)

/** Key into [MediaMetadata.extras] carrying the episode's feed ID (issue #179): [PlaybackController]
 *  reads this back off the player's current item rather than tracking its own feedId field, since
 *  [PlaybackService] can also change the current item directly (backgrounded auto-advance) without
 *  going through [PlaybackController.loadMedia]. */
const val FEED_ID_EXTRA_KEY = "com.bugzapperlabs.myfeeds.feedId"

/** Key into [MediaMetadata.extras] carrying the feed's volume boost, in
 *  [android.media.audiofx.LoudnessEnhancer] target-gain millibels (issue #199). Carried on the
 *  media item rather than looked up separately so [PlaybackService] can apply it synchronously
 *  from its player listener, the same way [FEED_ID_EXTRA_KEY] is read back. */
const val VOLUME_BOOST_EXTRA_KEY = "com.bugzapperlabs.myfeeds.volumeBoostMillibels"

/** Custom [androidx.media3.session.SessionCommand] letting [PlaybackController] change the
 *  volume boost of whatever's currently playing (issue #202) without a full media item reload --
 *  ordinary [androidx.media3.common.Player] commands have no notion of the
 *  [android.media.audiofx.LoudnessEnhancer] gain [PlaybackService] applies. */
const val CUSTOM_COMMAND_SET_VOLUME_BOOST = "com.bugzapperlabs.myfeeds.SET_VOLUME_BOOST"

/** Bundle key for the millibel value sent with [CUSTOM_COMMAND_SET_VOLUME_BOOST]. */
const val EXTRA_VOLUME_BOOST_MILLIBELS = "millibels"

/** Custom [androidx.media3.session.SessionCommand]s backing the media notification's
 *  skip-forward/skip-backward/cycle-speed buttons (issue #293). Standard seek-to-next/previous
 *  player commands don't apply here since Next Up is managed externally rather than through
 *  ExoPlayer's own timeline (issue #179), so [PlaybackService] can't rely on Media3's default
 *  notification actions for them and instead handles these directly in its session callback,
 *  the same way [CUSTOM_COMMAND_SET_VOLUME_BOOST] is handled. */
const val CUSTOM_COMMAND_SKIP_FORWARD = "com.bugzapperlabs.myfeeds.SKIP_FORWARD"
const val CUSTOM_COMMAND_SKIP_BACKWARD = "com.bugzapperlabs.myfeeds.SKIP_BACKWARD"
const val CUSTOM_COMMAND_CYCLE_SPEED = "com.bugzapperlabs.myfeeds.CYCLE_SPEED"

/** Speeds cycled by the notification's speed button (issue #293), matching [PLAYBACK_SPEEDS] in
 *  MiniPlayerBar/ReaderScreen so the notification offers the same presets as the in-app player. */
val NOTIFICATION_PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Resolves a [FeedItem] into playable Media3 pieces, shared by [PlaybackController] (playback
 * requests from the UI) and [PlaybackService] (issue #179: auto-advancing to the next Next Up
 * episode has to work with no UI/MediaController attached, so it can't go through
 * [PlaybackController]'s loadMedia).
 */
object PlaybackMediaItemFactory {
    /** Returns null without resolving anything if streaming is disallowed and nothing is downloaded. */
    suspend fun resolve(
        item: FeedItem,
        feedTitle: String?,
        feedRepository: FeedRepository,
        settingsDataStore: SettingsDataStore,
    ): ResolvedPlaybackMedia? {
        val allowStreaming = settingsDataStore.settings.first().allowPodcastStreaming
        val downloadedFilePath = item.downloadedFilePath?.takeIf { File(it).exists() }
        val uri = PlaybackUrlResolver.resolve(item, downloadedFilePath, allowStreaming = allowStreaming)
            ?: return null
        val feed = feedRepository.getFeed(item.feedId)
        val speed = feed?.playbackSpeed ?: 1.0f
        val artworkUrl = item.imageUrl ?: feed?.imageUrl
        val volumeBoostMillibels = feed?.volumeBoostMillibels ?: 0

        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(feedTitle)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setExtras(
                        Bundle().apply {
                            putLong(FEED_ID_EXTRA_KEY, item.feedId)
                            putInt(VOLUME_BOOST_EXTRA_KEY, volumeBoostMillibels)
                        },
                    )
                    .build(),
            )
            .build()

        // issue #200: only skip the feed's configured intro length on a genuinely fresh start --
        // enclosurePosition being set means either a real resume point or a completed episode
        // being replayed, neither of which should jump forward automatically.
        val startPositionMs = item.enclosurePosition?.let { (it * 1000).toLong() }
            ?: (feed?.startSkipSeconds ?: 0).toLong() * 1000L

        return ResolvedPlaybackMedia(
            mediaItem = mediaItem,
            speed = speed,
            startPositionMs = startPositionMs,
            volumeBoostMillibels = volumeBoostMillibels,
        )
    }
}
