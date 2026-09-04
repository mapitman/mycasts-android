package com.bugzapperlabs.mycasts.wear.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugzapperlabs.mycasts.wear.playback.WearPlaybackController
import com.bugzapperlabs.mycasts.wear.playback.WearPlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs [com.bugzapperlabs.mycasts.wear.nowplaying.NowPlayingScreen] (issue #276/#285) -- a thin
 *  pass-through to [WearPlaybackController], the same relationship `:app`'s in-page player has to
 *  [com.bugzapperlabs.mycasts.playback.PlaybackController]. */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackController: WearPlaybackController,
) : ViewModel() {
    val uiState: StateFlow<WearPlaybackUiState> = playbackController.uiState

    fun togglePlayPause() {
        if (uiState.value.isPlaying) playbackController.pause() else playbackController.resume()
    }

    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)

    fun skipForward() = playbackController.skipForward()

    fun skipBackward() = playbackController.skipBackward()

    fun cycleSpeed() = playbackController.cycleSpeed()

    fun nextEpisode() {
        viewModelScope.launch { playbackController.nextEpisode() }
    }

    fun previousEpisode() = playbackController.previousEpisode()
}
