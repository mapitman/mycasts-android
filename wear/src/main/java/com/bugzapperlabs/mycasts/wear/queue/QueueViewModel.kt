package com.bugzapperlabs.mycasts.wear.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.wear.playback.WearPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs [com.bugzapperlabs.mycasts.wear.queue.QueueScreen] (issue #276) -- the queue itself is
 *  local state, kept current by [com.bugzapperlabs.mycasts.wear.sync.WearSyncListenerService]
 *  applying snapshots pushed from the phone, not fetched or edited here. */
@HiltViewModel
class QueueViewModel @Inject constructor(
    queueRepository: QueueRepository,
    private val playbackController: WearPlaybackController,
) : ViewModel() {
    val queue: StateFlow<List<QueuedEpisode>> = queueRepository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentItemId: StateFlow<String?> = playbackController.uiState
        .map { it.currentItemId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Starts playing [item] (issue #276); [onStarted] navigates to the now-playing screen once
     *  playback actually starts, so a failed resolve (no `enclosureUrl`) leaves the user on the
     *  queue instead of navigating to a blank player. */
    fun play(item: FeedItem, onStarted: () -> Unit) {
        viewModelScope.launch {
            if (playbackController.play(item)) onStarted()
        }
    }
}
