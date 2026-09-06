package com.bugzapperlabs.mycasts.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val POSITION_TICK_MS = 500L

// Shared with WearPlaybackController's skip buttons (issue #285) -- matches :app's
// PlaybackController.SKIP_FORWARD_MS/SKIP_BACKWARD_MS amounts for a consistent skip feel.
internal const val SKIP_FORWARD_MS = 30_000L
internal const val SKIP_BACKWARD_MS = 15_000L

/** Speed presets cycled by the now-playing screen's speed control (issue #285), matching
 *  `:app`'s `NOTIFICATION_PLAYBACK_SPEEDS`. */
internal val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** UI-facing playback state on the watch (issue #276) -- trimmed from `:app`'s
 *  [com.bugzapperlabs.mycasts.playback.PlaybackUiState]: no chapters/volume-boost, neither of
 *  which the synced [com.bugzapperlabs.mycasts.data.local.Feed] snapshot carries or the watch UI
 *  is scoped to expose. */
data class WearPlaybackUiState(
    val currentItemId: String? = null,
    val title: String? = null,
    val feedTitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val artworkUrl: String? = null,
)

/**
 * UI-facing entry point for playback on the watch (issue #276), for whatever screens issue #276's
 * step 6 adds -- connects to [WearPlaybackService] via a [MediaController] and exposes its state
 * as a [StateFlow], mirroring `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackController].
 */
@Singleton
class WearPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedRepository: FeedRepository,
    private val queueRepository: QueueRepository,
) {
    private var controller: MediaController? = null

    private val _uiState = MutableStateFlow(WearPlaybackUiState())
    val uiState: StateFlow<WearPlaybackUiState> = _uiState.asStateFlow()

    private val positionTickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionTickerJob: Job? = null

    private fun snapshotState(player: Player) = WearPlaybackUiState(
        currentItemId = player.currentMediaItem?.mediaId,
        title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
        feedTitle = player.currentMediaItem?.mediaMetadata?.artist?.toString(),
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        positionMs = player.currentPosition,
        durationMs = player.duration.coerceAtLeast(0L),
        speed = player.playbackParameters.speed,
        artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
    )

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _uiState.value = if (player.playbackState == Player.STATE_ENDED && player.currentMediaItem == null) {
                WearPlaybackUiState()
            } else {
                snapshotState(player)
            }
        }
    }

    private fun connect(onConnected: (MediaController) -> Unit) {
        controller?.let {
            onConnected(it)
            return
        }
        val sessionToken = SessionToken(context, ComponentName(context, WearPlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                val mediaController = future.get()
                controller = mediaController
                mediaController.addListener(playerListener)
                startPositionTicker(mediaController)
                onConnected(mediaController)
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun startPositionTicker(player: Player) {
        positionTickerJob?.cancel()
        positionTickerJob = positionTickerScope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                if (player.isPlaying) _uiState.value = snapshotState(player)
            }
        }
    }

    /** Resolves and plays [item], moving it to the front of the local (synced) queue first --
     *  mirrors `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackController.play]. Returns
     *  false without starting playback if [item] can't be resolved (no `enclosureUrl`). */
    suspend fun play(item: FeedItem): Boolean {
        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository) ?: return false
        queueRepository.moveToFront(item.id)
        connect { controller ->
            controller.setMediaItem(resolved.mediaItem, resolved.startPositionMs)
            controller.prepare()
            controller.play()
        }
        return true
    }

    fun pause() {
        connect { it.pause() }
    }

    fun resume() {
        connect { it.play() }
    }

    fun seekTo(positionMs: Long) {
        connect { it.seekTo(positionMs) }
    }

    fun stop() {
        connect { it.stop() }
    }

    /** Issue #285: skip amounts mirror `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackController.skipForward]. */
    fun skipForward() {
        val playback = uiState.value
        seekTo((playback.positionMs + SKIP_FORWARD_MS).coerceAtMost(playback.durationMs))
    }

    fun skipBackward() {
        val playback = uiState.value
        seekTo((playback.positionMs - SKIP_BACKWARD_MS).coerceAtLeast(0L))
    }

    /** Cycles through [PLAYBACK_SPEEDS] (issue #285) -- a manual, session-only override, not
     *  persisted anywhere (unlike `:app`'s per-feed [com.bugzapperlabs.mycasts.data.local.Feed.playbackSpeed],
     *  which isn't part of the synced feed snapshot on the watch). */
    fun cycleSpeed() {
        val current = uiState.value.speed
        val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val nextSpeed = PLAYBACK_SPEEDS[(currentIndex + 1).mod(PLAYBACK_SPEEDS.size)]
        connect { it.setPlaybackSpeed(nextSpeed) }
    }

    /** Plays whatever's queued directly after the current front entry (issue #285) -- there's no
     *  play-history stack on the watch, so unlike a real "next track" jump this is really "advance
     *  the queue by one," the same effect [WearPlaybackService]'s own auto-advance has on
     *  completion, just user-triggered early. A no-op if nothing else is queued. */
    suspend fun nextEpisode() {
        val orderedIds = queueRepository.orderedItemIds()
        val currentIndex = orderedIds.indexOf(uiState.value.currentItemId)
        val nextId = orderedIds.getOrNull(currentIndex + 1) ?: return
        val item = feedRepository.getItem(nextId) ?: return
        play(item)
    }

    /** Restarts the current episode from the beginning (issue #285) -- with no play-history stack
     *  to jump back into, this is the only thing "previous" can sensibly mean here, matching how
     *  most media players treat "previous" once already at the start of the list. */
    fun previousEpisode() {
        seekTo(0L)
    }
}
