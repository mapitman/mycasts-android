package com.bugzapperlabs.mycasts.playback

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
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.bugzapperlabs.mycasts.MainActivity
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

// Safety cap on the advance-to-next-episode wake lock below, so a stuck/crashed advance can't
// hold it forever.
private const val ADVANCE_WAKE_LOCK_TIMEOUT_MS = 30_000L

// How much of the Next Up head episode to keep buffered ahead of time (issue #87). The observed
// silence gap between episodes was dominated by connection setup + first chunk, not by needing a
// deep buffer, so this is generous headroom rather than a tuned minimum.
private const val PRELOAD_TARGET_MS = 15_000L

/**
 * Trivial by design (issue #87): this app only ever preloads a single candidate at a time (the
 * Next Up head), unlike a carousel with many ranked candidates, so there's no need to track a
 * moving "current index" to decide what's worth preloading -- whatever's been added always is.
 */
@androidx.media3.common.util.UnstableApi
private class NextEpisodePreloadStatusControl : TargetPreloadStatusControl<Int> {
    override fun getTargetPreloadStatus(rankingData: Int): TargetPreloadStatusControl.PreloadStatus =
        DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS, PRELOAD_TARGET_MS)
}

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

    @Inject
    lateinit var networkTypeChecker: NetworkTypeChecker

    private lateinit var player: ExoPlayer

    @UnstableApi
    private lateinit var preloadManager: DefaultPreloadManager
    private lateinit var advanceWakeLock: PowerManager.WakeLock
    private var mediaSession: MediaSession? = null

    // The Next Up head's resolved media, kept in sync with the queue by preloadQueueHeadJob below
    // (issue #87) -- cached (rather than re-resolving at transition time) so playNextQueued() can
    // look its MediaItem up in preloadManager by reference-equal match, and so a resolve() that
    // depends on settings/position doesn't drift between when it was registered for preload and
    // when it's actually needed a few seconds/minutes later.
    private var preloadedNext: Pair<String, ResolvedPlaybackMedia>? = null

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

    // Same guard as advancingFromEnded, but for onPlayerError (issue #201) -- kept separate since
    // the two can be in flight for entirely unrelated items (an error on a freshly-started episode
    // while a previous STATE_ENDED advance's own bookkeeping coroutine hasn't finished yet).
    private var advancingFromError = false

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
        // Routed through DefaultPreloadManager.Builder (issue #87) rather than built directly, so
        // the Next Up head episode can be warming up (connection opened, first chunk buffered) in
        // the background while the current one still plays -- see preloadQueueHeadJob below. All
        // of ExoPlayer's own configuration below is unchanged from before that; the preload
        // manager just needs to build the player itself to wire in its shared internals.
        val preloadManagerBuilder = DefaultPreloadManager.Builder(this, NextEpisodePreloadStatusControl())
        preloadManager = preloadManagerBuilder.build()
        player = preloadManagerBuilder.buildExoPlayer(
            ExoPlayer.Builder(this)
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
                .setHandleAudioBecomingNoisy(true),
        )
        player.addListener(playerListener)
        loudnessEnhancer = runCatching { LoudnessEnhancer(player.audioSessionId) }.getOrNull()
        startPreloadingQueueHead()

        // ExoPlayer's own WAKE_MODE_NETWORK lock only covers actively-playing time -- it releases
        // the instant an episode hits STATE_ENDED, before the auto-advance coroutine below has run
        // (issue #179). With the screen off and nothing else holding a wake lock in that gap, the
        // system can suspend the CPU before the next episode's connection ever opens. This lock
        // covers exactly that transition window instead.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        advanceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mycasts:playback-advance")

        // Without an explicit small icon, DefaultMediaNotificationProvider falls back to the
        // app's adaptive launcher icon (android.R.attr.icon), which the system can't flatten
        // into the monochrome silhouette a notification/lock-screen icon needs, so it renders
        // blank. (issue #116) MyCastsMediaNotificationProvider also swaps in skip-forward/
        // skip-backward/cycle-speed buttons in place of the default (non-functional here)
        // seek-to-next/seek-to-previous actions (issue #293).
        setMediaNotificationProvider(
            MyCastsMediaNotificationProvider(this).apply {
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

    /**
     * Keeps [preloadManager] warming up whatever's next in line after the episode currently
     * playing (issue #87), reacting to every mutation that can change it -- add/remove/reorder/
     * auto-queue eviction all flow through [QueueRepository.observeQueue]. Runs for the service's
     * whole lifetime, not gated on anything currently playing, so the head is already warm by the
     * time an episode actually finishes and [playNextQueued] needs it.
     *
     * Skips the queue's own front entry when it matches [Player.getCurrentMediaItem] (issue #196):
     * the currently-playing episode is itself a real queue entry now, always at the front, so the
     * "head" this preloads has to be the entry *after* it -- preloading the front entry itself
     * would just be re-preloading whatever's already playing.
     */
    @OptIn(markerClass = [UnstableApi::class])
    private fun startPreloadingQueueHead() {
        serviceScope.launch {
            queueRepository.observeQueue().map { queue -> queue.firstOrNull { it.item.id != player.currentMediaItem?.mediaId } }
                .distinctUntilChanged { old, new -> old?.item?.id == new?.item?.id }
                .collect { head ->
                    preloadManager.reset()
                    preloadedNext = null
                    if (head == null) return@collect
                    val resolved = PlaybackMediaItemFactory.resolve(head.item, head.feedTitle, feedRepository, settingsDataStore, networkTypeChecker)
                        ?: return@collect
                    preloadedNext = head.item.id to resolved
                    preloadManager.add(resolved.mediaItem, /* rankingData= */ 0)
                    preloadManager.invalidate()
                }
        }
    }

    /** Pushes the skip/speed buttons through the session itself, not just the notification
     *  provider (issue #293) -- see [buildSkipAndSpeedMediaButtonPreferences]'s doc for why both
     *  are needed. Called again whenever the speed changes so the system media card's speed
     *  button label stays in sync, mirroring how the notification's own label refreshes on every
     *  rebuild via [MyCastsMediaNotificationProvider.getMediaButtons]. */
    @OptIn(markerClass = [UnstableApi::class])
    private fun updateMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(
            buildSkipAndSpeedMediaButtonPreferences(this, player.playbackParameters.speed),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    @OptIn(markerClass = [UnstableApi::class])
    override fun onDestroy() {
        positionSaveJob?.cancel()
        if (advanceWakeLock.isHeld) advanceWakeLock.release()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.let { session ->
            player.release()
            preloadManager.release()
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
            // unavailable per the class-level comment on MyCastsMediaNotificationProvider) -- and
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
                // MyCastsMediaNotificationProvider).
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
            // issue #201: a broken episode (e.g. its downloaded file was deleted or corrupted, or
            // a stream failed outright) otherwise leaves the player parked on it forever --
            // isPlaying goes false, but currentMediaItem never changes, so the UI keeps showing it
            // as merely "paused" rather than surfacing the failure or moving on. Skip it the same
            // way a naturally-finished episode advances, minus the finished-episode bookkeeping
            // (setEnclosurePosition/markRead/auto-delete) below -- this episode never actually
            // completed, so none of that applies.
            if (advancingFromError) return
            val itemId = player.currentMediaItem?.mediaId
            advancingFromError = true
            advanceWakeLock.acquire(ADVANCE_WAKE_LOCK_TIMEOUT_MS)
            serviceScope.launch {
                try {
                    // issue #196: the failed episode is still the queue's own front entry (it
                    // never stopped being "current" until now) -- move it out of the front before
                    // peeking the next one, or playNextQueued would just find this same broken
                    // episode again and loop on it forever. Moved to the back rather than removed
                    // outright: unlike a finished episode, this one never actually played, so it's
                    // still worth surfacing in Next Up for the user to retry later.
                    itemId?.let { queueRepository.moveToEnd(it) }
                    playNextQueued(excludeItemId = itemId)
                } finally {
                    advancingFromError = false
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED || advancingFromEnded) return
            val itemId = player.currentMediaItem?.mediaId ?: return
            advancingFromEnded = true
            advanceWakeLock.acquire(ADVANCE_WAKE_LOCK_TIMEOUT_MS)
            serviceScope.launch {
                try {
                    // Starts the next episode first (issue #82): none of the finished episode's
                    // bookkeeping below is a prerequisite for it, and every DB write/settings read
                    // done beforehand was pure added silence between episodes, on top of whatever
                    // buffering the next episode's own prepare() needs (worse for a streamed
                    // episode than a downloaded one, since that also has to open a connection).
                    // issue #196: the finished episode is still the queue's own front entry (it
                    // never stopped being "current" until now) -- excluded here rather than
                    // removed upfront (which would reintroduce the silence gap issue #82 just
                    // eliminated), since playNextQueued would otherwise just find this same
                    // already-finished episode again. The row itself is dropped for real just
                    // below, alongside the finished-episode bookkeeping it belongs next to.
                    playNextQueued(excludeItemId = itemId)
                    queueRepository.remove(itemId)
                    feedRepository.setEnclosurePosition(itemId, null)
                    feedRepository.markRead(itemId, true)
                    // Storage cap / auto-cleanup (issue #71): only ever deletes an episode that
                    // just finished playing in full, never one still in progress or unplayed.
                    if (settingsDataStore.settings.first().autoDeleteFinishedDownloads) {
                        feedRepository.getItem(itemId)
                            ?.takeIf { it.downloadedFilePath != null }
                            ?.let { downloadRepository.deleteDownload(it) }
                    }
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
    // issue #196: the currently-playing episode is now a real queue entry itself (the front one),
    // kept there for as long as it's playing rather than popped off on advance the way the old
    // pop-and-remove semantics did -- see peekFront()'s doc. [excludeItemId] covers the one case
    // that still needs to look past the front entry regardless: a just-failed episode moved to the
    // back of the queue (PlaybackService.onPlayerError's moveToEnd) has nowhere to actually move to
    // if it was the queue's only entry, so it would otherwise still be sitting at the front here,
    // and get "advanced" into again -- the exact same broken episode, looping forever.
    @OptIn(markerClass = [UnstableApi::class])
    private suspend fun playNextQueued(excludeItemId: String? = null) {
        val itemId = queueRepository.peekFront()?.takeIf { it != excludeItemId }
        if (itemId == null) {
            player.clearMediaItems()
            settingsDataStore.setLastPlayingItem(null, null)
            return
        }
        // lastPlayingItem is cleared on every early-return path below (issue #82) -- this used to
        // be set unconditionally by the caller before playNextQueued() ran at all, but that clear
        // is now deferred to here so it doesn't add to the silence gap ahead of the *success*
        // path's player.prepare()/play() below.
        //
        // Reuses startPreloadingQueueHead's cached resolve for this exact item id if it's still
        // current (issue #87), rather than resolving fresh -- both to avoid a redundant DB read
        // and because getMediaSource() below matches by the same MediaItem instance passed to
        // preloadManager.add(), so a freshly re-resolved MediaItem wouldn't match it anyway.
        val cachedPreload = preloadedNext?.takeIf { it.first == itemId }?.second
        val resolved = cachedPreload ?: run {
            val item = feedRepository.getItem(itemId) ?: run { settingsDataStore.setLastPlayingItem(null, null); return }
            val feed = feedRepository.getFeed(item.feedId)
            PlaybackMediaItemFactory.resolve(item, feed?.userTitle ?: feed?.title, feedRepository, settingsDataStore, networkTypeChecker)
                ?: run { settingsDataStore.setLastPlayingItem(null, null); return }
        }
        val feedId = resolved.mediaItem.mediaMetadata.extras?.getLong(FEED_ID_EXTRA_KEY)
        val preloadedSource = cachedPreload?.let { preloadManager.getMediaSource(resolved.mediaItem) }
        if (preloadedSource != null) {
            player.setMediaSource(preloadedSource)
            player.seekTo(resolved.startPositionMs)
        } else {
            player.setMediaItem(resolved.mediaItem, resolved.startPositionMs)
        }
        player.setPlaybackSpeed(resolved.speed)
        player.prepare()
        player.play()
        settingsDataStore.setLastPlayingItem(feedId, itemId)
        preloadedNext = null
        preloadManager.reset()
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
