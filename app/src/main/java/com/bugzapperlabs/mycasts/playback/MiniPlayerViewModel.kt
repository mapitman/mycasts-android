package com.bugzapperlabs.mycasts.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-scoped wrapper around [PlaybackController.uiState] for the persistent mini-player
 * (issue #66) -- separate from [com.bugzapperlabs.mycasts.episodedetails.EpisodeDetailsViewModel] since the mini-player
 * lives outside the episode details screen's SavedStateHandle-scoped feedId/itemId and only needs play/pause/skip/stop.
 *
 * A single instance of this is created in `MainActivity` and passed down explicitly to
 * `QueueScreen` (issue #96), rather than each obtaining its own via `hiltViewModel()` -- they'd
 * otherwise be two separate instances (one activity-scoped, one scoped to the "queue" nav
 * destination), which [pendingExpand] needs to not be, for tapping the mini strip elsewhere to be
 * able to signal QueueScreen's player sheet to expand once navigation lands there.
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
) : ViewModel() {
    val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

    private val _pendingExpand = MutableStateFlow(false)

    /** Set by [requestExpand] when the mini strip is tapped from outside the Next Up tab -- tells
     *  `QueueScreen`'s player sheet to expand itself once navigation lands there, rather than just
     *  arriving peeked. Cleared by [consumeExpandRequest] once acted on. */
    val pendingExpand: StateFlow<Boolean> = _pendingExpand.asStateFlow()

    fun requestExpand() {
        _pendingExpand.value = true
        // Mutually exclusive with a pending collapse (issue #148): e.g. the sheet is left
        // expanded on Next Up, the user leaves (queuing a collapse for next time), then taps the
        // mini strip elsewhere to explicitly reopen it -- that later, more specific request
        // should win the race rather than an indeterminate collection order between the two
        // LaunchedEffects deciding it.
        _pendingCollapse.value = false
    }

    fun consumeExpandRequest() {
        _pendingExpand.value = false
    }

    private val _toggleSheetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted by [toggleSheet] when the Next Up tab is tapped while already on the Next Up
     *  screen (issue #148): `QueueScreen` flips its player sheet between peeked and expanded in
     *  response, repeating on each subsequent tap. A `SharedFlow` rather than `pendingExpand`'s
     *  `StateFlow`, since each tap is a one-shot toggle event rather than a persistent target
     *  state -- collapsing back down needs its own event too. */
    val toggleSheetRequests: SharedFlow<Unit> = _toggleSheetRequests.asSharedFlow()

    fun toggleSheet() {
        _toggleSheetRequests.tryEmit(Unit)
    }

    private val _pendingCollapse = MutableStateFlow(false)

    /** Set by [requestCollapse] when the Next Up tab is tapped from a different tab (issue #148)
     *  -- QueueScreen's `rememberBottomSheetScaffoldState` survives the tab switch (it's saved/
     *  restored, not recreated), so an already-expanded sheet would otherwise stay expanded
     *  rather than landing minimized. Cleared by [consumeCollapseRequest] once acted on. */
    val pendingCollapse: StateFlow<Boolean> = _pendingCollapse.asStateFlow()

    fun requestCollapse() {
        _pendingCollapse.value = true
        _pendingExpand.value = false
    }

    fun consumeCollapseRequest() {
        _pendingCollapse.value = false
    }

    /** Restores the last-playing episode (issue #108); safe to call every app launch. */
    fun restoreLastPlayingItem() {
        viewModelScope.launch { playbackController.restoreLastPlayingItem() }
    }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) playbackController.pause() else playbackController.resume()
    }

    fun skipForward() {
        playbackController.skipForward()
    }

    fun skipBackward() {
        playbackController.skipBackward()
    }

    fun nextChapter() {
        playbackController.nextChapter()
    }

    fun previousChapter() {
        playbackController.previousChapter()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed)
    }

    fun setVolumeBoost(millibels: Int) {
        playbackController.setVolumeBoost(millibels)
    }

    fun stop() {
        playbackController.stop()
    }
}
