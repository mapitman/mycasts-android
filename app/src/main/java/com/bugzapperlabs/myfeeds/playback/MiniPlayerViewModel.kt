package com.bugzapperlabs.myfeeds.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-scoped wrapper around [PlaybackController.uiState] for the persistent mini-player
 * (issue #66) -- separate from [com.bugzapperlabs.myfeeds.reader.ReaderViewModel] since the mini-player
 * lives outside the reader's SavedStateHandle-scoped feedId/itemId and only needs play/pause/skip/stop.
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
) : ViewModel() {
    val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

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
