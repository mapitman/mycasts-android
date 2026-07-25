package com.bugzapperlabs.myfeeds.feedproperties

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.bugzapperlabs.myfeeds.data.local.AutoQueuePosition
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedPropertiesUiState(
    val title: String = "",
    val displayTitle: String = "",
    val feedUrl: String? = null,
    val itemsToKeep: Int? = null,
    val globalMaxArticles: Int = 20,
    val autoDownloadEnabled: Boolean = false,
    val maxDownloadsToKeep: Int? = null,
    val autoQueueEnabled: Boolean = false,
    val autoQueueMaxCount: Int? = null,
    val autoQueuePosition: AutoQueuePosition = AutoQueuePosition.BOTTOM,
    val playbackSpeed: Float = 1.0f,
    val volumeBoostMillibels: Int = 0,
    val startSkipSeconds: Int = 0,
    val isUnsubscribed: Boolean = false,
    val isPodcastFeed: Boolean = false,
)

@HiltViewModel
class FeedPropertiesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedRepository: FeedRepository,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val feedId: Long = checkNotNull(savedStateHandle["feedId"])

    val uiState: StateFlow<FeedPropertiesUiState> = combine(
        feedRepository.observeFeed(feedId),
        settingsDataStore.settings,
        feedRepository.observePodcastFeedIds(),
    ) { feed, settings, podcastFeedIds ->
        if (feed == null) {
            FeedPropertiesUiState(isUnsubscribed = true)
        } else {
            FeedPropertiesUiState(
                title = feed.userTitle ?: feed.title.orEmpty(),
                displayTitle = feed.userTitle ?: feed.title.orEmpty(),
                feedUrl = feed.feedUrl,
                itemsToKeep = feed.itemsToKeep,
                globalMaxArticles = settings.maxArticles,
                autoDownloadEnabled = feed.autoDownloadEnabled,
                maxDownloadsToKeep = feed.maxDownloadsToKeep,
                autoQueueEnabled = feed.autoQueueEnabled,
                autoQueueMaxCount = feed.autoQueueMaxCount,
                autoQueuePosition = feed.autoQueuePosition,
                playbackSpeed = feed.playbackSpeed,
                volumeBoostMillibels = feed.volumeBoostMillibels,
                startSkipSeconds = feed.startSkipSeconds,
                isPodcastFeed = feedId in podcastFeedIds,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedPropertiesUiState())

    fun setTitle(title: String) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            val trimmed = title.trim()
            val userTitle = if (trimmed.isBlank() || trimmed == feed.title) null else trimmed
            feedRepository.updateFeed(feed.copy(userTitle = userTitle))
        }
    }

    fun setItemsToKeep(itemsToKeep: Int?) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(itemsToKeep = itemsToKeep))
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(autoDownloadEnabled = enabled))
        }
    }

    fun setMaxDownloadsToKeep(maxCount: Int?) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(maxDownloadsToKeep = maxCount))
        }
    }

    fun setAutoQueueEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(autoQueueEnabled = enabled))
        }
    }

    fun setAutoQueueMaxCount(maxCount: Int?) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(autoQueueMaxCount = maxCount))
        }
    }

    fun setAutoQueuePosition(position: AutoQueuePosition) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(autoQueuePosition = position))
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(playbackSpeed = speed))
        }
    }

    fun setVolumeBoostMillibels(millibels: Int) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(volumeBoostMillibels = millibels))
        }
    }

    fun setStartSkipSeconds(seconds: Int) {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.updateFeed(feed.copy(startSkipSeconds = seconds))
        }
    }

    fun unsubscribe() {
        viewModelScope.launch {
            val feed = feedRepository.getFeed(feedId) ?: return@launch
            feedRepository.unsubscribe(feed)
        }
    }
}
