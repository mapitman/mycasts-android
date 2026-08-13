package com.bugzapperlabs.mycasts.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    }

    fun consumeExpandRequest() {
        _pendingExpand.value = false
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
