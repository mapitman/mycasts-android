package com.bugzapperlabs.myfeeds.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import com.bugzapperlabs.myfeeds.playback.PlaybackController
import com.bugzapperlabs.myfeeds.playback.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val items: List<FeedItem> = emptyList(),
    val initialIndex: Int = 0,
    val feedTitle: String? = null,
    val feedImageUrl: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedRepository: FeedRepository,
    private val playbackController: PlaybackController,
    private val downloadRepository: EnclosureDownloadRepository,
    private val queueRepository: QueueRepository,
    settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val feedId: Long = checkNotNull(savedStateHandle["feedId"])
    private val initialItemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _queueFeedback = MutableStateFlow<String?>(null)

    /** One-shot add-to-queue confirmation for a Snackbar (issue #144); cleared via [consumeQueueFeedback]. */
    val queueFeedback: StateFlow<String?> = _queueFeedback

    val uiState: StateFlow<ReaderUiState> = combine(
        feedRepository.observeItems(feedId),
        feedRepository.observeFeed(feedId),
    ) { items, feed ->
        val index = items.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
        ReaderUiState(items = items, initialIndex = index, feedTitle = feed?.userTitle ?: feed?.title, feedImageUrl = feed?.imageUrl)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

    /**
     * Item IDs currently in the "Next Up" queue, kept live so the reader's per-page toggle
     * (issue #160) reflects queue changes made anywhere (this screen, the queue screen, etc.)
     * without needing to key a lookup on the pager's current item.
     */
    val queuedItemIds: StateFlow<Set<String>> = queueRepository.observeQueue()
        .map { queued -> queued.map { it.item.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val articleFontSize: StateFlow<FontSize> = settingsDataStore.settings
        .map { it.articleFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.NORMAL)

    /**
     * Articles are marked read as soon as they're viewed. Podcast episodes are only marked
     * read/played when playback finishes (see [com.bugzapperlabs.myfeeds.playback.PlaybackService]).
     */
    fun markRead(item: FeedItem) {
        if (item.isPodcastEpisode) return
        viewModelScope.launch { feedRepository.markRead(item.id, true) }
    }

    fun togglePlayPause(item: FeedItem) {
        val playback = playbackState.value
        when {
            playback.currentItemId == item.id && playback.isPlaying -> playbackController.pause()
            playback.currentItemId == item.id && !playback.isPlaying -> playbackController.resume()
            else -> viewModelScope.launch { playbackController.play(item, uiState.value.feedTitle) }
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
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

    fun setPlaybackSpeed(speed: Float) {
        playbackController.setSpeed(speed)
    }

    fun setVolumeBoost(millibels: Int) {
        playbackController.setVolumeBoost(millibels)
    }

    fun downloadEnclosure(item: FeedItem) {
        viewModelScope.launch { downloadRepository.startDownload(item) }
    }

    fun deleteDownload(item: FeedItem) {
        viewModelScope.launch { downloadRepository.deleteDownload(item) }
    }

    fun addToQueue(itemId: String) {
        viewModelScope.launch {
            val added = queueRepository.addToEnd(itemId)
            _queueFeedback.value = context.getString(
                if (added) R.string.queue_feedback_added else R.string.queue_feedback_already_queued,
            )
        }
    }

    /** Removes an episode from the "Next Up" queue (issue #160 toggle, tapping again removes it). */
    fun removeFromQueue(itemId: String) {
        viewModelScope.launch { queueRepository.remove(itemId) }
    }

    fun consumeQueueFeedback() {
        _queueFeedback.value = null
    }
}
