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

/** UI-facing playback state on the watch (issue #276) -- trimmed from `:app`'s
 *  [com.bugzapperlabs.mycasts.playback.PlaybackUiState]: no chapters/volume-boost/speed
 *  cycling, none of which the synced [com.bugzapperlabs.mycasts.data.local.Feed] snapshot carries
 *  or the watch UI (issue #276's step 6) is scoped to expose. */
data class WearPlaybackUiState(
    val currentItemId: String? = null,
    val title: String? = null,
    val feedTitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
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
}
