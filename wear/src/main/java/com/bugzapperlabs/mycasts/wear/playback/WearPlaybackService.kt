package com.bugzapperlabs.mycasts.wear.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.sync.WearSyncClient
import com.bugzapperlabs.mycasts.wear.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WearPlaybackService"
private const val POSITION_SAVE_INTERVAL_MS = 5_000L

/**
 * The watch's own playback engine (issue #276) -- a trimmed [MediaSessionService], mirroring
 * `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackService] but without its preload manager/
 * timeline-lookahead machinery, Android Auto browse tree, or downloaded-file branch: this streams
 * one episode at a time directly from [WearPlaybackMediaItemFactory], auto-advancing to the local
 * (synced) queue's next entry on completion.
 */
@AndroidEntryPoint
class WearPlaybackService : MediaSessionService() {

    @Inject
    lateinit var feedRepository: FeedRepository

    @Inject
    lateinit var queueRepository: QueueRepository

    @Inject
    lateinit var wearSyncClient: WearSyncClient

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null
    private var advancingFromEnded = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        startPositionSaveLoop()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED || advancingFromEnded) return
            val itemId = player.currentMediaItem?.mediaId ?: return
            advancingFromEnded = true
            serviceScope.launch {
                try {
                    onEpisodeFinished(itemId)
                    playNextQueued()
                } finally {
                    advancingFromEnded = false
                }
            }
        }
    }

    /** Loads and plays the local queue's new front entry once the previous episode has been
     *  removed from it (issue #276), or clears the player if the queue is now empty. Mirrors
     *  `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackService.playNextQueued], trimmed --
     *  no lookahead cache/preload manager to reuse a cached resolve from, no retry-on-dangling-entry
     *  loop (a synced queue entry with no matching local `FeedItem` shouldn't happen: [WearQueueSyncApplier]
     *  always inserts a queue's items before its `QueueEntry` rows). */
    private suspend fun playNextQueued() {
        val itemId = queueRepository.peekFront()
        if (itemId == null) {
            player.clearMediaItems()
            return
        }
        val item = feedRepository.getItem(itemId) ?: return
        val resolved = WearPlaybackMediaItemFactory.resolve(item, feedRepository) ?: return
        player.setMediaItem(resolved.mediaItem, resolved.startPositionMs)
        player.prepare()
        player.play()
    }

    private suspend fun onEpisodeFinished(itemId: String) {
        queueRepository.remove(itemId)
        feedRepository.setEnclosurePosition(itemId, null)
    }

    private fun startPositionSaveLoop() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                saveCurrentPosition()
                delay(POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private fun saveCurrentPosition() {
        val itemId = player.currentMediaItem?.mediaId ?: return
        val positionMs = player.currentPosition
        serviceScope.launch { feedRepository.setEnclosurePosition(itemId, positionMs / 1000.0) }
        // issue #276: mirrors :app's PlaybackService -- best-effort, never allowed to affect the
        // local save above (no paired phone right now is a perfectly normal state, not an error).
        serviceScope.launch {
            runCatching { wearSyncClient.putPosition(itemId, positionMs, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Failed to push position to phone", it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        positionSaveJob?.cancel()
        mediaSession?.let { session ->
            player.release()
            session.release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
