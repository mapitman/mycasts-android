package com.bugzapperlabs.mycasts.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
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
const val FEED_ID_EXTRA_KEY = "com.bugzapperlabs.mycasts.feedId"

/** Key into [MediaMetadata.extras] carrying the feed's volume boost, in
 *  [android.media.audiofx.LoudnessEnhancer] target-gain millibels (issue #199). Carried on the
 *  media item rather than looked up separately so [PlaybackService] can apply it synchronously
 *  from its player listener, the same way [FEED_ID_EXTRA_KEY] is read back. */
const val VOLUME_BOOST_EXTRA_KEY = "com.bugzapperlabs.mycasts.volumeBoostMillibels"

/** Key into [MediaMetadata.extras] carrying the feed's configured playback speed (issue #256):
 *  lets [PlaybackService]'s player listener reapply the right speed on *any* media item
 *  transition -- including one Android Auto/a MediaController triggers directly via the player's
 *  own multi-item timeline (e.g. `seekToNextMediaItem()`), which bypasses the explicit
 *  `player.setPlaybackSpeed(...)` calls at each of [PlaybackService]'s own call sites. */
const val PLAYBACK_SPEED_EXTRA_KEY = "com.bugzapperlabs.mycasts.playbackSpeed"

/** Key into [MediaMetadata.extras] carrying the resolved start position in milliseconds (issue
 *  #256) -- carried for reference/parity with [PLAYBACK_SPEED_EXTRA_KEY] and
 *  [VOLUME_BOOST_EXTRA_KEY], but deliberately *not* applied generically on every transition the
 *  way those two are: unlike speed/volume, re-seeking on every transition would fight a user's own
 *  in-progress seek, so start position stays an explicit one-time seek at each existing call site
 *  ([PlaybackController.loadMedia], [PlaybackService.playNextQueued]/onSetMediaItems/
 *  onPlaybackResumption) instead. */
const val START_POSITION_MS_EXTRA_KEY = "com.bugzapperlabs.mycasts.startPositionMs"

/** Custom [androidx.media3.session.SessionCommand] letting [PlaybackController] change the
 *  volume boost of whatever's currently playing (issue #202) without a full media item reload --
 *  ordinary [androidx.media3.common.Player] commands have no notion of the
 *  [android.media.audiofx.LoudnessEnhancer] gain [PlaybackService] applies. */
const val CUSTOM_COMMAND_SET_VOLUME_BOOST = "com.bugzapperlabs.mycasts.SET_VOLUME_BOOST"

/** Bundle key for the millibel value sent with [CUSTOM_COMMAND_SET_VOLUME_BOOST]. */
const val EXTRA_VOLUME_BOOST_MILLIBELS = "millibels"

/** Custom [androidx.media3.session.SessionCommand]s backing the media notification's
 *  skip-forward/skip-backward/cycle-speed buttons (issue #293). These are fixed-amount seeks, not
 *  standard seek-to-next/previous player commands -- deliberately, to stay consistent with the
 *  hardware media-button handling in [PlaybackService]'s `onMediaButtonEvent` (issue #244), even
 *  though a real "next episode" now exists on the player's own timeline (issue #256).
 *  [PlaybackService] handles these directly in its session callback, the same way
 *  [CUSTOM_COMMAND_SET_VOLUME_BOOST] is handled. */
const val CUSTOM_COMMAND_SKIP_FORWARD = "com.bugzapperlabs.mycasts.SKIP_FORWARD"
const val CUSTOM_COMMAND_SKIP_BACKWARD = "com.bugzapperlabs.mycasts.SKIP_BACKWARD"
const val CUSTOM_COMMAND_CYCLE_SPEED = "com.bugzapperlabs.mycasts.CYCLE_SPEED"

/** Speeds cycled by the notification's speed button (issue #293), matching [PLAYBACK_SPEEDS] in
 *  MiniPlayerBar/EpisodeDetailsScreen so the notification offers the same presets as the in-app player. */
val NOTIFICATION_PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * Resolves a [FeedItem] into playable Media3 pieces, shared by [PlaybackController] (playback
 * requests from the UI) and [PlaybackService] (issue #179: auto-advancing to the next Next Up
 * episode has to work with no UI/MediaController attached, so it can't go through
 * [PlaybackController]'s loadMedia).
 */
object PlaybackMediaItemFactory {
    /**
     * Returns null without resolving anything if streaming is disallowed and nothing is
     * downloaded. Wi-Fi streaming is always allowed (issue #123); [networkTypeChecker] only gates
     * the mobile-data case (issue #221; [NetworkTypeChecker.isActiveNetworkCellular] itself is
     * still named after the underlying Android transport type it checks, not the app's own
     * "mobile data" terminology), checked once here at resolve (i.e. playback-start) time -- a
     * stream already in progress is allowed to keep playing through a later network change, so
     * this never needs rechecking mid-session. Defaults to "never on mobile data" since most
     * callers (tests exercising unrelated behavior, primarily) don't care about this gating at all.
     *
     * [forceAllowStreaming] bypasses the mobile-data gate outright (issue #222) -- set only by
     * [PlaybackController] after the user has explicitly confirmed the play-time mobile-data
     * warning for this one episode, never by [PlaybackService]'s non-interactive queue-driven
     * auto-advance (issue #179), which has no UI to show that warning from and so always resolves
     * with the default `false`, same gating as before this issue.
     */
    suspend fun resolve(
        item: FeedItem,
        feedTitle: String?,
        feedRepository: FeedRepository,
        settingsDataStore: SettingsDataStore,
        networkTypeChecker: NetworkTypeChecker = NetworkTypeChecker { false },
        forceAllowStreaming: Boolean = false,
    ): ResolvedPlaybackMedia? {
        val settings = settingsDataStore.settings.first()
        val allowStreaming = forceAllowStreaming ||
            !networkTypeChecker.isActiveNetworkCellular() ||
            settings.alwaysAllowPodcastStreamingOnMobileData
        val downloadedFilePath = item.downloadedFilePath?.takeIf { File(it).exists() }
        val uri = PlaybackUrlResolver.resolve(item, downloadedFilePath, allowStreaming = allowStreaming)
            ?: return null
        val feed = feedRepository.getFeed(item.feedId)
        val speed = feed?.playbackSpeed ?: 1.0f
        val artworkUrl = item.imageUrl ?: feed?.imageUrl
        val volumeBoostMillibels = feed?.volumeBoostMillibels ?: 0

        // issue #200: only skip the feed's configured intro length on a genuinely fresh start --
        // enclosurePosition being set means either a real resume point or a completed episode
        // being replayed, neither of which should jump forward automatically.
        val startPositionMs = item.enclosurePosition?.let { (it * 1000).toLong() }
            ?: (feed?.startSkipSeconds ?: 0).toLong() * 1000L

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
                            putFloat(PLAYBACK_SPEED_EXTRA_KEY, speed)
                            putLong(START_POSITION_MS_EXTRA_KEY, startPositionMs)
                        },
                    )
                    .build(),
            )
            .build()

        return ResolvedPlaybackMedia(
            mediaItem = mediaItem,
            speed = speed,
            startPositionMs = startPositionMs,
            volumeBoostMillibels = volumeBoostMillibels,
        )
    }

    /**
     * Whether starting [item] right now would need to stream it over mobile data without the
     * user having said "always allow" (issue #222) -- [PlaybackController] checks this before
     * [resolve] to decide whether to show a confirmation prompt instead of just playing (or, for
     * an item that's downloaded or reachable over Wi-Fi, playing immediately with no prompt at
     * all, same as before this issue).
     */
    suspend fun needsMobileDataConfirmation(
        item: FeedItem,
        settingsDataStore: SettingsDataStore,
        networkTypeChecker: NetworkTypeChecker,
    ): Boolean {
        val downloadedFilePath = item.downloadedFilePath?.takeIf { File(it).exists() }
        if (downloadedFilePath != null) return false
        if (!networkTypeChecker.isActiveNetworkCellular()) return false
        val settings = settingsDataStore.settings.first()
        return !settings.alwaysAllowPodcastStreamingOnMobileData
    }
}
