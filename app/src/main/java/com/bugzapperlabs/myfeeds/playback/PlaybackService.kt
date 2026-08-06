package com.bugzapperlabs.myfeeds.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.bugzapperlabs.myfeeds.MainActivity
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

// Safety cap on the advance-to-next-episode wake lock below, so a stuck/crashed advance can't
// hold it forever.
private const val ADVANCE_WAKE_LOCK_TIMEOUT_MS = 30_000L

/**
 * Ported from MyFeeds.AudioPlaybackAgent AudioPlayer.cs OnPlayStateChanged: while playing, saves
 * the current position every 5s (the original used 1s against isolated-storage LINQ-to-SQL; DB
 * writes here are cheap enough but 5s is plenty for resume granularity). On pause/stop it saves
 * once more; on completion it clears the saved position and marks the item read, mirroring
 * TrackEnded's `SavePosition(0)` + `SetItemAsRead`.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var feedRepository: FeedRepository

    @Inject
    lateinit var downloadRepository: EnclosureDownloadRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var queueRepository: QueueRepository

    private lateinit var player: ExoPlayer
    private lateinit var advanceWakeLock: PowerManager.WakeLock
    private var mediaSession: MediaSession? = null

    // Boosts loudness beyond ExoPlayer's unity-gain volume cap (issue #199), keyed to the
    // player's audio session ID which is fixed for the lifetime of this ExoPlayer instance. Some
    // devices/OEMs don't ship the underlying effect, so creation is best-effort.
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null

    // Guards against onPlaybackStateChanged(STATE_ENDED) firing again (issue #125/#127's original
    // failure mode, now guarded here instead of PlaybackController -- issue #179) before the
    // coroutine handling the first call has advanced the player off STATE_ENDED.
    private var advancingFromEnded = false

    @OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        // ExoPlayer.Builder defaults to *not* managing audio focus at all, so without this,
        // playback talks straight over things like a navigation app's turn-by-turn announcements
        // instead of pausing for them and resuming after (issue #180). AudioAttributes.DEFAULT's
        // content type is AUDIO_CONTENT_TYPE_UNKNOWN, under which Media3's AudioFocusManager only
        // ducks (lowers volume) rather than pauses on a transient "may duck" focus loss -- the
        // request type nav apps use for spoken prompts -- since its willPauseWhenDucked() only
        // returns true for AUDIO_CONTENT_TYPE_SPEECH. Podcast speech talking under a nav prompt at
        // reduced volume is still unintelligible, so mark the content as speech to get an outright
        // pause/resume instead of a duck.
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            // Without this, continuing an already-open stream in the background works fine, but
            // opening a *new* one (auto-advancing to the next Next Up episode while backgrounded,
            // issue #179) can stall indefinitely once the device dozes / Wi-Fi goes idle, since
            // nothing is holding a CPU/Wi-Fi wake lock to let the new connection actually open.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Defaults to false, so without this, audio rerouted from a disconnecting Bluetooth
            // device (or unplugged wired headphones) keeps playing out loud on the speaker
            // instead of pausing (issue #243).
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)
        loudnessEnhancer = runCatching { LoudnessEnhancer(player.audioSessionId) }.getOrNull()

        // ExoPlayer's own WAKE_MODE_NETWORK lock only covers actively-playing time -- it releases
        // the instant an episode hits STATE_ENDED, before the auto-advance coroutine below has run
        // (issue #179). With the screen off and nothing else holding a wake lock in that gap, the
        // system can suspend the CPU before the next episode's connection ever opens. This lock
        // covers exactly that transition window instead.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        advanceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myfeeds:playback-advance")

        // Without an explicit small icon, DefaultMediaNotificationProvider falls back to the
        // app's adaptive launcher icon (android.R.attr.icon), which the system can't flatten
        // into the monochrome silhouette a notification/lock-screen icon needs, so it renders
        // blank. (issue #116) MyFeedsMediaNotificationProvider also swaps in skip-forward/
        // skip-backward/cycle-speed buttons in place of the default (non-functional here)
        // seek-to-next/seek-to-previous actions (issue #293).
        setMediaNotificationProvider(
            MyFeedsMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(mediaSessionCallback)
            .build()
        updateMediaButtonPreferences()
    }

    /** Pushes the skip/speed buttons through the session itself, not just the notification
     *  provider (issue #293) -- see [buildSkipAndSpeedMediaButtonPreferences]'s doc for why both
     *  are needed. Called again whenever the speed changes so the system media card's speed
     *  button label stays in sync, mirroring how the notification's own label refreshes on every
     *  rebuild via [MyFeedsMediaNotificationProvider.getMediaButtons]. */
    @OptIn(markerClass = [UnstableApi::class])
    private fun updateMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(
            buildSkipAndSpeedMediaButtonPreferences(this, player.playbackParameters.speed),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        positionSaveJob?.cancel()
        if (advanceWakeLock.isHeld) advanceWakeLock.release()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.let { session ->
            player.release()
            session.release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Grants [CUSTOM_COMMAND_SET_VOLUME_BOOST] to controllers (issue #202) and applies it live to
     *  [loudnessEnhancer], since it's not a standard [MediaSession]/[Player] command. */
    private val mediaSessionCallback = object : MediaSession.Callback {
        @OptIn(markerClass = [UnstableApi::class]) // DEFAULT_SESSION_AND_LIBRARY_COMMANDS / accept() (issue #212)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_SET_VOLUME_BOOST, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SKIP_BACKWARD, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_CYCLE_SPEED, Bundle.EMPTY))
                .build()
            // COMMAND_SEEK_TO_PREVIOUS is Media3's generic "restart the current item" command --
            // distinct from COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM (real playlist navigation, already
            // unavailable per the class-level comment on MyFeedsMediaNotificationProvider) -- and
            // it's *always* available since it doesn't need an actual previous item. Left granted,
            // the system's media notification/lock-screen UI renders it as its own restart-from-0
            // button alongside our skip-backward-15/skip-forward-30 buttons (issue #293), which is
            // redundant and confusing. Removing it (and its seek-to-next counterpart, for symmetry)
            // leaves only our own custom skip buttons as the transport controls.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        // Bluetooth remotes vary in which key codes their forward/back buttons actually send --
        // some send FAST_FORWARD/REWIND, but others (issue #244, confirmed on-device) send
        // NEXT/PREVIOUS instead. Media3's default handling maps NEXT/PREVIOUS to
        // seekToNext()/seekToPrevious(), which only operate on the player's own timeline; since
        // Next Up is managed externally and only ever one MediaItem is loaded at a time (issue
        // #179), that left the forward button doing nothing (no next item to seek to) and the
        // back button restarting the current episode (seekToPrevious()'s no-previous-item
        // fallback) instead of skipping by a fixed amount either way. Handling all four codes here
        // directly, matching PlaybackController.skipForward()/skipBackward()'s amounts (issue #66).
        @OptIn(markerClass = [UnstableApi::class])
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            } ?: return false
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return false
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ->
                    player.seekTo((player.currentPosition + SKIP_FORWARD_MS).coerceAtMost(player.duration.coerceAtLeast(0)))
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ->
                    player.seekTo((player.currentPosition - SKIP_BACKWARD_MS).coerceAtLeast(0))
                else -> return false
            }
            return true
        }

        @OptIn(markerClass = [UnstableApi::class]) // SessionError (issue #212)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_COMMAND_SET_VOLUME_BOOST -> {
                    applyVolumeBoost(args.getInt(EXTRA_VOLUME_BOOST_MILLIBELS, 0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // The notification's own skip-forward/skip-backward buttons (issue #293), using the
                // same fixed seek amounts as onMediaButtonEvent above and PlaybackController's
                // skipForward()/skipBackward(), since the standard seek-to-next/previous player
                // commands don't apply here (see the class-level comment on
                // MyFeedsMediaNotificationProvider).
                CUSTOM_COMMAND_SKIP_FORWARD -> {
                    player.seekTo((player.currentPosition + SKIP_FORWARD_MS).coerceAtMost(player.duration.coerceAtLeast(0)))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_SKIP_BACKWARD -> {
                    player.seekTo((player.currentPosition - SKIP_BACKWARD_MS).coerceAtLeast(0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Cycles through the same speed presets as the in-app player's speed control
                // (issue #293), wrapping back to the first once the last preset is passed.
                CUSTOM_COMMAND_CYCLE_SPEED -> {
                    val currentSpeed = player.playbackParameters.speed
                    val currentIndex = NOTIFICATION_PLAYBACK_SPEEDS.indexOfFirst { (it - currentSpeed).let { d -> d < 0.01f && d > -0.01f } }
                    val nextSpeed = NOTIFICATION_PLAYBACK_SPEEDS[(currentIndex + 1).mod(NOTIFICATION_PLAYBACK_SPEEDS.size)]
                    player.setPlaybackSpeed(nextSpeed)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val millibels = mediaItem?.mediaMetadata?.extras?.getInt(VOLUME_BOOST_EXTRA_KEY, 0) ?: 0
            applyVolumeBoost(millibels)
        }

        // Keeps the media button preferences' speed-cycle button label in sync (issue #293),
        // covering both CUSTOM_COMMAND_CYCLE_SPEED presses and a new episode loading at its feed's
        // configured default speed.
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            updateMediaButtonPreferences()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startPositionSaveLoop()
                // Release the advance wake lock (issue #241) once the auto-advanced episode is
                // actually playing rather than in the coroutine's finally block below --
                // prepare()/play() return before buffering completes, so releasing right after
                // calling them left the CPU free to doze mid-buffer with the screen off, before
                // the new stream's connection ever finished opening.
                if (advanceWakeLock.isHeld) advanceWakeLock.release()
            } else {
                positionSaveJob?.cancel()
                // Skip the final save on end-of-track: onPlaybackStateChanged's STATE_ENDED
                // branch below already clears the position, and this callback can otherwise fire
                // after it and overwrite the clear with the last (near-duration) position.
                if (player.playbackState != Player.STATE_ENDED) {
                    saveCurrentPosition()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Advance failed to start (issue #241) -- don't hold the wake lock for its full
            // timeout when we already know playback isn't coming.
            if (advanceWakeLock.isHeld) advanceWakeLock.release()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED || advancingFromEnded) return
            val itemId = player.currentMediaItem?.mediaId ?: return
            advancingFromEnded = true
            advanceWakeLock.acquire(ADVANCE_WAKE_LOCK_TIMEOUT_MS)
            serviceScope.launch {
                try {
                    feedRepository.setEnclosurePosition(itemId, null)
                    feedRepository.markRead(itemId, true)
                    // Storage cap / auto-cleanup (issue #71): only ever deletes an episode that
                    // just finished playing in full, never one still in progress or unplayed.
                    if (settingsDataStore.settings.first().autoDeleteFinishedDownloads) {
                        feedRepository.getItem(itemId)
                            ?.takeIf { it.downloadedFilePath != null }
                            ?.let { downloadRepository.deleteDownload(it) }
                    }
                    settingsDataStore.setLastPlayingItem(null, null)
                    playNextQueued()
                    // The wake lock is released once playback actually starts (onIsPlayingChanged
                    // above) or fails (onPlayerError above), not here -- see issue #241. The
                    // ADVANCE_WAKE_LOCK_TIMEOUT_MS cap still bounds it if neither ever fires.
                } finally {
                    advancingFromEnded = false
                }
            }
        }
    }

    /**
     * Auto-advances to whatever's next in Next Up when an episode finishes (issue #106), acting
     * directly on [player] rather than through a [androidx.media3.session.MediaController] --
     * issue #179: this has to keep working even with no UI/MediaController attached (backgrounded
     * or screen off), and this service is the one guaranteed to still be running when that happens.
     */
    private suspend fun playNextQueued() {
        val itemId = queueRepository.popNext()
        if (itemId == null) {
            player.clearMediaItems()
            return
        }
        val item = feedRepository.getItem(itemId) ?: return
        val feed = feedRepository.getFeed(item.feedId)
        val resolved = PlaybackMediaItemFactory.resolve(item, feed?.userTitle ?: feed?.title, feedRepository, settingsDataStore)
            ?: return
        player.setMediaItem(resolved.mediaItem, resolved.startPositionMs)
        player.setPlaybackSpeed(resolved.speed)
        player.prepare()
        player.play()
        settingsDataStore.setLastPlayingItem(item.feedId, item.id)
    }

    private fun startPositionSaveLoop() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                saveCurrentPosition()
                delay(5_000)
            }
        }
    }

    /** Sets [loudnessEnhancer]'s live gain -- called both with the boost baked into a newly loaded
     *  media item (issue #199) and with a live override pushed by [PlaybackController] via
     *  [CUSTOM_COMMAND_SET_VOLUME_BOOST] while the same episode keeps playing (issue #202). */
    private fun applyVolumeBoost(millibels: Int) {
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            enhancer.setTargetGain(millibels)
            enhancer.enabled = millibels > 0
        }
    }

    private fun saveCurrentPosition() {
        val itemId = player.currentMediaItem?.mediaId ?: return
        val positionSeconds = player.currentPosition / 1000.0
        serviceScope.launch { feedRepository.setEnclosurePosition(itemId, positionSeconds) }
    }
}
