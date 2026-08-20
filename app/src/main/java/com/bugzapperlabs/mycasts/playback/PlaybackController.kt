package com.bugzapperlabs.mycasts.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Shared with PlaybackService, which applies the same amounts to ExoPlayer's seekForward()/
// seekBack() so a Bluetooth device's hardware FAST_FORWARD/REWIND buttons (issue #244) skip by
// the same amount as the in-app controls instead of falling back to Media3's own defaults.
internal const val SKIP_FORWARD_MS = 30_000L
internal const val SKIP_BACKWARD_MS = 15_000L
private const val POSITION_TICK_MS = 500L

/** issue #95: "previous chapter" restarts the current chapter if more than this far into it,
 *  otherwise jumps to the actual previous chapter -- mirrors standard previous-track UX. */
private const val PREVIOUS_CHAPTER_RESTART_THRESHOLD_MS = 5_000L

data class PlaybackUiState(
    val currentItemId: String? = null,
    val feedId: Long? = null,
    val title: String? = null,
    val feedTitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isEnded: Boolean = false,
    val speed: Float = 1.0f,
    val volumeBoostMillibels: Int = 0,
    val artworkUrl: String? = null,
    /** Chapters for the current episode (issue #95), fetched lazily and possibly still empty
     *  while the fetch is in flight, or permanently empty if the episode has none. */
    val chapters: List<Chapter> = emptyList(),
) {
    /** The chapter containing the current playback position, if any. */
    val currentChapter: Chapter?
        get() = chapters.lastOrNull { it.startTimeMs <= positionMs }

    val currentChapterIndex: Int
        get() = chapters.indexOfLast { it.startTimeMs <= positionMs }
}

/**
 * UI-facing entry point for playback (used by the in-article player, issue #20): connects to
 * [PlaybackService] via a [MediaController] and exposes its state as a [StateFlow]. Resolving the
 * media via [PlaybackMediaItemFactory] (rather than in the service) keeps the streaming-gate check
 * ([PlaybackUrlResolver]) next to the caller, which needs to know whether the request was denied.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val feedRepository: FeedRepository,
    private val queueRepository: QueueRepository,
    private val chaptersFetcher: ChaptersFetcher,
    private val networkTypeChecker: NetworkTypeChecker,
) {
    private var controller: MediaController? = null

    // Rebuilt into every PlaybackUiState by snapshotState (issue #95); tracked here rather than
    // read back out of _uiState.value since it's fetched asynchronously and independently of the
    // player-driven state snapshots.
    private var currentChapters: List<Chapter> = emptyList()

    // Media3's MediaItem/MediaMetadata have no first-class "feedId" field, so it's tracked
    // alongside the controller and folded into PlaybackUiState -- the mini-player (issue #66)
    // needs it to navigate to the episode details route ("episodeDetails/{feedId}/{itemId}") on tap.
    private var currentFeedId: Long? = null

    // Tracked separately from uiState.currentItemId (which only updates once the player listener
    // fires) so loadMedia can synchronously tell what was playing right before a switch, to
    // re-queue it (issue #106).
    private var currentItemId: String? = null

    // Issue #202: unlike speed, volume boost isn't a real Player property -- it only ever exists as
    // this locally tracked value (pushed to PlaybackService via a custom session command) plus the
    // baked-in initial value on each MediaItem's extras. Re-read from those extras in onEvents only
    // when currentItemId actually changes (a real track transition), not on every event, so a
    // setVolumeBoost() call made mid-episode isn't immediately clobbered back to the stale value
    // that was baked in when this item first loaded.
    private var currentVolumeBoostMillibels: Int = 0

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    // Player.Listener.onEvents only fires on discrete state changes (play/pause/seek/media item
    // transition/etc.), never on a timer -- without this, positionMs (and the progress bar/elapsed
    // time driven by it, issue #75) would sit frozen at wherever it was during ongoing playback.
    private val positionTickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionTickerJob: Job? = null

    /**
     * Test-only teardown (issues #54/#60): cancels [positionTickerScope] and waits until every
     * coroutine launched into it has actually finished, so nothing is left mid-flight to dispatch
     * onto `Dispatchers.Main` after a test's `Dispatchers.resetMain()`. Production never calls
     * this -- the controller is an app-lifetime @Singleton whose scope is never torn down.
     */
    @VisibleForTesting
    internal suspend fun awaitShutdownForTest() {
        positionTickerScope.coroutineContext.job.cancelAndJoin()
    }

    private fun snapshotState(player: Player) = PlaybackUiState(
        currentItemId = player.currentMediaItem?.mediaId,
        feedId = player.currentMediaItem?.mediaMetadata?.extras?.getLong(FEED_ID_EXTRA_KEY)?.takeIf { it != 0L },
        title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
        feedTitle = player.currentMediaItem?.mediaMetadata?.artist?.toString(),
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        positionMs = player.currentPosition,
        durationMs = player.duration.coerceAtLeast(0L),
        isEnded = player.playbackState == Player.STATE_ENDED,
        speed = player.playbackParameters.speed,
        volumeBoostMillibels = currentVolumeBoostMillibels,
        artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
        chapters = currentChapters,
    )

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_ENDED) {
                handlePlaybackEnded()
            } else {
                // Keeps these fields (used by setSpeed/requeuePreviousEpisode/restoreLastPlayingItem)
                // in sync even when the current item changed via a path that doesn't call loadMedia,
                // e.g. PlaybackService's backgrounded auto-advance (issue #179).
                currentFeedId = player.currentMediaItem?.mediaMetadata?.extras?.getLong(FEED_ID_EXTRA_KEY)?.takeIf { it != 0L }
                val newItemId = player.currentMediaItem?.mediaId
                if (newItemId != currentItemId) {
                    currentVolumeBoostMillibels = player.currentMediaItem?.mediaMetadata?.extras
                        ?.getInt(VOLUME_BOOST_EXTRA_KEY, 0) ?: 0
                    // issue #202: this branch also covers a transition that didn't go through
                    // loadMedia (PlaybackService's own backgrounded auto-advance, issue #179) --
                    // without re-fetching here too, currentChapters kept showing the previous
                    // episode's chapters (or none) for every episode Next Up advanced into on its
                    // own, which in practice is most of them.
                    currentChapters = emptyList()
                    if (newItemId != null) loadChaptersById(newItemId)
                }
                currentItemId = newItemId
                _uiState.value = snapshotState(player)
            }
        }
    }

    /**
     * On completion the episode is no longer "current" (issue #107): position resets and the item
     * stops being treated as playing -- same UI-reset as an explicit [stop], but the episode is
     * actually finished here (unlike [stop], issue #191) so it's not requeued. If anything is up
     * next, [PlaybackService] (not
     * here -- issue #179) advances the underlying player to it directly, and the next
     * [Player.Listener.onEvents] this controller receives will reflect that automatically via the
     * normal (non-ended) branch below, so the mini-player picks it up without this needing to do
     * anything further.
     *
     * [Player.Listener.onEvents] can fire more than once while [player] still reports
     * `STATE_ENDED` -- guarded the same way as [PlaybackService]'s own end-of-track handling, so
     * this reset doesn't happen redundantly for one completion.
     */
    private fun handlePlaybackEnded() {
        if (currentItemId == null) return
        currentFeedId = null
        currentItemId = null
        currentChapters = emptyList()
        _uiState.value = PlaybackUiState()
        positionTickerJob?.cancel()
    }

    private fun connect(onConnected: (MediaController) -> Unit) {
        controller?.let {
            onConnected(it)
            return
        }
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
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

    /**
     * Returns false without starting playback if streaming is disallowed and nothing is
     * downloaded. Moves [item] to the front of the Next Up queue unconditionally, even when
     * playback then fails to start (issue #196): the currently-playing episode is meant to be a
     * real, visible queue entry -- always the front one -- rather than hidden from Next Up
     * entirely, so it shows up there clearly marked as playing instead of only via the mini/expanded
     * player. Mirrors the move-to-front-then-play behavior [QueueRepository] callers used to have
     * to do themselves (e.g. the old `QueueViewModel.playNow`), but now for every path that starts
     * playback.
     */
    suspend fun play(item: FeedItem, feedTitle: String?): Boolean {
        queueRepository.moveToFront(item.id)
        return loadMedia(item, feedTitle, autoPlay = true)
    }

    /**
     * Restores the episode the player had loaded before the process died (issue #108), so the
     * mini-player reappears ready to resume without auto-starting playback. A no-op if something
     * is already loaded (e.g. this raced with the user starting playback some other way) or if
     * nothing was persisted, already finished, or has since been removed from the feed.
     */
    suspend fun restoreLastPlayingItem() {
        if (currentFeedId != null) return
        val settings = settingsDataStore.settings.first()
        val feedId = settings.lastPlayingFeedId ?: return
        val itemId = settings.lastPlayingItemId ?: return
        val item = feedRepository.getItem(itemId)?.takeIf { it.feedId == feedId && !it.isRead } ?: return
        val feed = feedRepository.getFeed(feedId)
        // issue #196: keeps the restored episode as the queue's front entry too, the same as any
        // other path that makes an episode "current" -- it may already be there (a normal resume),
        // but a cold start after the app's own process died mid-playback is also how a
        // still-current episode could end up missing from the queue table altogether.
        queueRepository.moveToFront(itemId)
        loadMedia(item, feed?.userTitle ?: feed?.title, autoPlay = false)
    }

    private suspend fun loadMedia(item: FeedItem, feedTitle: String?, autoPlay: Boolean): Boolean {
        val resolved = PlaybackMediaItemFactory.resolve(item, feedTitle, feedRepository, settingsDataStore, networkTypeChecker)
            ?: return false

        val previousItemId = currentItemId
        if (previousItemId != null && previousItemId != item.id) {
            dropFromQueueIfFinished(previousItemId)
        }

        currentFeedId = item.feedId
        currentItemId = item.id
        currentVolumeBoostMillibels = resolved.volumeBoostMillibels
        // Cleared synchronously so the previous episode's chapters never briefly show for the new
        // one while the fetch below (if any) is still in flight.
        currentChapters = emptyList()
        connect { controller ->
            controller.setMediaItem(resolved.mediaItem, resolved.startPositionMs)
            controller.setPlaybackSpeed(resolved.speed)
            controller.prepare()
            if (autoPlay) controller.play()
        }
        settingsDataStore.setLastPlayingItem(item.feedId, item.id)
        loadChapters(item)
        return true
    }

    /** Fetched asynchronously so a slow/unreachable chapters host never delays playback starting. */
    private fun loadChapters(item: FeedItem) {
        val chaptersUrl = item.chaptersUrl ?: return
        fetchAndApplyChapters(item.id, chaptersUrl)
    }

    /** Same as [loadChapters], but for a transition PlaybackController only learned about via
     *  [playerListener] (issue #202) -- e.g. [PlaybackService]'s own backgrounded auto-advance
     *  (issue #179) -- so there's no already-in-hand [FeedItem] to read chaptersUrl off of. */
    private fun loadChaptersById(itemId: String) {
        positionTickerScope.launch {
            val chaptersUrl = feedRepository.getItem(itemId)?.chaptersUrl ?: return@launch
            fetchAndApplyChapters(itemId, chaptersUrl)
        }
    }

    private fun fetchAndApplyChapters(itemId: String, chaptersUrl: String) {
        positionTickerScope.launch {
            val chapters = chaptersFetcher.fetch(chaptersUrl)
            // The user may have already switched to another episode by the time this resolves.
            if (currentItemId != itemId) return@launch
            currentChapters = chapters
            _uiState.value = _uiState.value.copy(chapters = chapters)
        }
    }

    /**
     * Whatever was playing before a switch stays in "Next Up" as long as it wasn't finished
     * (issue #106) -- since issue #196, it's already a real queue entry the whole time it's
     * playing (see [play]/[QueueRepository.moveToFront]), so there's nothing to *add* here; this
     * only needs to drop it if it turns out to have actually finished (mirrors
     * [restoreLastPlayingItem]'s `!isRead` check for "not completed") -- a finished episode
     * shouldn't linger in Next Up just because it still happened to be the queue's front entry
     * when playback moved on. A no-op if it was never queued in the first place, or the user
     * already removed it from Next Up themselves while it was still playing.
     */
    private suspend fun dropFromQueueIfFinished(itemId: String) {
        val previous = feedRepository.getItem(itemId)?.takeIf { it.isRead } ?: return
        queueRepository.remove(previous.id)
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    /**
     * Changes speed for the current playback session and persists it as the playing episode's
     * feed's default (issue #70), so future episodes from that feed start at the chosen speed.
     */
    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        val feedId = currentFeedId ?: return
        positionTickerScope.launch(Dispatchers.IO) {
            feedRepository.getFeed(feedId)?.let { feedRepository.updateFeed(it.copy(playbackSpeed = speed)) }
        }
    }

    /**
     * Changes volume boost for the current playback session and persists it as the playing
     * episode's feed's default (issue #202), mirroring [setSpeed]. Unlike speed, boost isn't a
     * standard [Player] property -- [PlaybackService] applies it via a
     * [android.media.audiofx.LoudnessEnhancer], so it's pushed there through a custom session
     * command rather than a [MediaController] method. Updated optimistically here too so the
     * player UI reflects the change immediately rather than waiting on a round trip.
     */
    fun setVolumeBoost(millibels: Int) {
        currentVolumeBoostMillibels = millibels
        _uiState.value = _uiState.value.copy(volumeBoostMillibels = millibels)
        controller?.sendCustomCommand(
            SessionCommand(CUSTOM_COMMAND_SET_VOLUME_BOOST, Bundle.EMPTY),
            Bundle().apply { putInt(EXTRA_VOLUME_BOOST_MILLIBELS, millibels) },
        )
        val feedId = currentFeedId ?: return
        positionTickerScope.launch(Dispatchers.IO) {
            feedRepository.getFeed(feedId)?.let { feedRepository.updateFeed(it.copy(volumeBoostMillibels = millibels)) }
        }
    }

    // Shared by the episode details screen's inline controls and the mini-player (issue #66) so both skip by the
    // same amount rather than each view re-implementing the offset/clamping logic.
    fun skipForward() {
        val playback = uiState.value
        seekTo((playback.positionMs + SKIP_FORWARD_MS).coerceAtMost(playback.durationMs))
    }

    fun skipBackward() {
        val playback = uiState.value
        seekTo((playback.positionMs - SKIP_BACKWARD_MS).coerceAtLeast(0L))
    }

    /** Jumps to the start of the next chapter, if any (issue #95). No-op past the last chapter. */
    fun nextChapter() {
        val playback = uiState.value
        val next = playback.chapters.firstOrNull { it.startTimeMs > playback.positionMs } ?: return
        seekTo(next.startTimeMs)
    }

    /**
     * More than [PREVIOUS_CHAPTER_RESTART_THRESHOLD_MS] into the current chapter, restarts it;
     * otherwise jumps to the actual previous chapter (or the very start, if already in the first
     * one) -- mirrors standard previous-track UX (issue #95).
     */
    fun previousChapter() {
        val playback = uiState.value
        val currentIndex = playback.currentChapterIndex
        if (currentIndex < 0) return
        val currentChapter = playback.chapters[currentIndex]
        val elapsedInChapter = playback.positionMs - currentChapter.startTimeMs
        val target = if (elapsedInChapter > PREVIOUS_CHAPTER_RESTART_THRESHOLD_MS) {
            currentChapter.startTimeMs
        } else {
            playback.chapters.getOrNull(currentIndex - 1)?.startTimeMs ?: 0L
        }
        seekTo(target)
    }

    fun stop() {
        // Player.stop() alone halts playback but retains currentMediaItem, which would leave
        // the mini-player (issue #66) stuck on-screen -- clearMediaItems() is what actually
        // drops it so currentItemId (and therefore mini-player visibility) goes back to null.
        val itemId = currentItemId
        controller?.stop()
        controller?.clearMediaItems()
        currentFeedId = null
        currentItemId = null
        currentChapters = emptyList()
        positionTickerScope.launch(Dispatchers.IO) {
            settingsDataStore.setLastPlayingItem(null, null)
            // Explicit stop just ends "now playing" -- unlike natural completion, the episode
            // isn't finished, so it stays in Next Up the same way switching to a different episode
            // already does (issue #191); it's already there as the front entry (issue #196), so
            // this is only a safety net for the edge case where it's since been marked read.
            itemId?.let { dropFromQueueIfFinished(it) }
        }
    }

    fun release() {
        positionTickerJob?.cancel()
        controller?.release()
        controller = null
    }
}
