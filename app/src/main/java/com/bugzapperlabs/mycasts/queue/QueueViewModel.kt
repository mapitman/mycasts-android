package com.bugzapperlabs.mycasts.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An episode just removed from the queue (issue #284), kept around long enough for [QueueViewModel.undoRemove]
 *  to put it back exactly where it was. */
data class RemovedQueueEpisode(val episode: QueuedEpisode, val entry: QueueEntry)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queueRepository: QueueRepository,
    private val feedRepository: FeedRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {
    val queue: StateFlow<List<QueuedEpisode>> = queueRepository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The currently-playing episode's id (issue #96), so a queue row for it -- e.g. one still
     *  mid-flight through [ReorderableQueueList]'s own optimistic state right after [playNow] --
     *  can be highlighted instead of just disabling its long-press. */
    val currentItemId: StateFlow<String?> = playbackController.uiState
        .map { it.currentItemId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _removedEpisode = MutableStateFlow<RemovedQueueEpisode?>(null)

    /** Set by [remove], cleared by [consumeRemovedEpisode] -- the UI shows an Undo snackbar for
     *  whatever this currently holds. */
    val removedEpisode: StateFlow<RemovedQueueEpisode?> = _removedEpisode.asStateFlow()

    /**
     * Renumbers the queue to match [orderedItemIds] exactly, for drag-to-reorder. [onComplete]
     * runs once the write lands -- the caller uses it to release its own optimistic-ordering
     * guard, so an unrelated `queue` re-emission racing this write can't overwrite the drag's
     * result with the stale pre-reorder order before it's persisted.
     */
    fun reorder(orderedItemIds: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            queueRepository.reorder(orderedItemIds)
            onComplete()
        }
    }

    /** Whether the next call to [sortByPublishDate] (issue #17) sorts oldest-first or
     *  newest-first -- flips after each sort, defaulting to ascending (oldest first) so the
     *  first tap matches the issue's requested default. */
    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    /**
     * Reorders the queue by episode publish date (issue #17) -- unlike [reorder], this actually
     * rewrites [com.bugzapperlabs.mycasts.data.local.QueueEntry.position] for every entry rather
     * than just transforming the display, so it also changes playback order the same way a manual
     * drag would; the user can still drag afterward to fine-tune from there. Episodes with no
     * known publish date sort to one end depending on direction (Kotlin's nulls-first default,
     * reversed for descending).
     */
    fun sortByPublishDate() {
        val ascending = _sortAscending.value
        val sorted = if (ascending) {
            queue.value.sortedBy { it.item.publishDate }
        } else {
            queue.value.sortedByDescending { it.item.publishDate }
        }
        _sortAscending.value = !ascending
        reorder(sorted.map { it.item.id })
    }

    /** Removes the episode and, if it really was queued, stashes it so [undoRemove] can put it
     *  back (issue #284) -- looked up from the current [queue] snapshot rather than threading a
     *  whole [QueuedEpisode] through every caller just for its title. */
    fun remove(itemId: String) {
        val episode = queue.value.find { it.item.id == itemId } ?: return
        viewModelScope.launch {
            val entry = queueRepository.remove(itemId) ?: return@launch
            _removedEpisode.value = RemovedQueueEpisode(episode, entry)
        }
    }

    /** Puts an episode removed via [remove] back at its exact former position. A no-op if it's
     *  since been queued again some other way (e.g. re-added manually). */
    fun undoRemove(removed: RemovedQueueEpisode) {
        viewModelScope.launch { queueRepository.restore(removed.entry) }
        if (_removedEpisode.value == removed) _removedEpisode.value = null
    }

    /** Clears the pending Undo state once the snackbar offering it has been shown and dismissed
     *  (or its action taken), so it doesn't reappear on the next recomposition. */
    fun consumeRemovedEpisode() {
        _removedEpisode.value = null
    }

    /**
     * Plays this episode immediately. [PlaybackController.play] removes it from the queue itself
     * (issue #171), since it's no longer "up next" once playing.
     */
    fun playNow(episode: QueuedEpisode) {
        viewModelScope.launch {
            if (playbackController.uiState.value.currentItemId == episode.item.id) return@launch
            val feed = feedRepository.getFeed(episode.item.feedId)
            playbackController.play(episode.item, feed?.userTitle ?: feed?.title)
        }
    }
}
