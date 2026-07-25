package com.bugzapperlabs.myfeeds.articlelist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateResult
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArticleListUiState(
    val feedTitle: String = "",
    val showUnreadOnly: Boolean = true,
    val articles: List<FeedItem> = emptyList(),
    val unreadCount: Int = 0,
    val selectedIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val isPodcastFeed: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArticleListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer,
    private val queueRepository: QueueRepository,
    settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val feedId: Long = checkNotNull(savedStateHandle["feedId"])

    private val showUnreadOnly = MutableStateFlow(true)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val feedTitle = MutableStateFlow("")
    private val isRefreshing = MutableStateFlow(false)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _queueFeedback = MutableStateFlow<String?>(null)

    /** One-shot refresh-failure message for a Snackbar; cleared via [consumeRefreshError]. */
    val refreshError: StateFlow<String?> = _refreshError

    /** One-shot add-to-queue confirmation for a Snackbar (issue #126); cleared via [consumeQueueFeedback]. */
    val queueFeedback: StateFlow<String?> = _queueFeedback

    val uiState: StateFlow<ArticleListUiState> = combine(
        feedTitle,
        showUnreadOnly,
        showUnreadOnly.flatMapLatest { unreadOnly ->
            if (unreadOnly) feedRepository.observeUnreadItems(feedId) else feedRepository.observeItems(feedId)
        },
        feedRepository.observeUnreadCount(feedId),
        selectedIds,
    ) { title, unreadOnly, articles, unreadCount, selected ->
        ArticleListUiState(title, unreadOnly, articles, unreadCount, selected)
    }.combine(isRefreshing) { state, refreshing ->
        state.copy(isRefreshing = refreshing)
    }.combine(feedRepository.observePodcastFeedIds()) { state, podcastFeedIds ->
        state.copy(isPodcastFeed = feedId in podcastFeedIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleListUiState())

    init {
        viewModelScope.launch {
            val defaultToAllView = settingsDataStore.settings.first().defaultToAllArticleView
            showUnreadOnly.value = !defaultToAllView

            val feed = feedRepository.getFeed(feedId)
            feedTitle.value = feed?.userTitle ?: feed?.title.orEmpty()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            val feed = feedRepository.getFeed(feedId)
            if (feed != null) {
                val result = feedUpdateEngine.updateFeed(feed)
                autoQueueAndDownloadEnforcer.apply(listOf(result))
                if (result is FeedUpdateResult.Failure) {
                    _refreshError.value = context.getString(R.string.feed_list_refresh_error)
                }
            }
            isRefreshing.value = false
        }
    }

    fun consumeRefreshError() {
        _refreshError.value = null
    }

    val listFontSize: StateFlow<FontSize> = settingsDataStore.settings
        .map { it.listFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.NORMAL)

    fun setShowUnreadOnly(unreadOnly: Boolean) {
        showUnreadOnly.value = unreadOnly
    }

    fun toggleSelection(itemId: String) {
        selectedIds.value = if (itemId in selectedIds.value) {
            selectedIds.value - itemId
        } else {
            selectedIds.value + itemId
        }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** Selects every currently-visible/filtered article (issue #72). */
    fun selectAll() {
        selectedIds.value = uiState.value.articles.map { it.id }.toSet()
    }

    fun markSelectedRead(isRead: Boolean) {
        val ids = selectedIds.value
        viewModelScope.launch {
            ids.forEach { feedRepository.markRead(it, isRead) }
            clearSelection()
        }
    }

    /** Swipe-to-toggle on a single row (issue #120), independent of multi-select. */
    fun toggleRead(item: FeedItem) {
        viewModelScope.launch { feedRepository.markRead(item.id, !item.isRead) }
    }

    fun deleteSelected() {
        val ids = selectedIds.value
        viewModelScope.launch {
            val items = uiState.value.articles.filter { it.id in ids }
            feedRepository.deleteItems(items)
            clearSelection()
        }
    }

    fun addToQueue(itemId: String) {
        viewModelScope.launch {
            val added = queueRepository.addToEnd(itemId)
            _queueFeedback.value = context.getString(
                if (added) R.string.queue_feedback_added else R.string.queue_feedback_already_queued,
            )
        }
    }

    /**
     * Adds every selected podcast episode to Next Up (issue #159). Selection mode isn't
     * podcast-specific, so non-episode articles in the selection are silently skipped.
     */
    fun addSelectedToQueue() {
        val ids = selectedIds.value
        viewModelScope.launch {
            val episodeIds = uiState.value.articles.filter { it.id in ids && it.isPodcastEpisode }.map { it.id }
            val addedCount = episodeIds.count { queueRepository.addToEnd(it) }
            _queueFeedback.value = when (addedCount) {
                0 -> context.getString(R.string.queue_feedback_already_queued)
                1 -> context.getString(R.string.queue_feedback_added)
                else -> context.getString(R.string.queue_feedback_added_multiple, addedCount)
            }
            clearSelection()
        }
    }

    fun consumeQueueFeedback() {
        _queueFeedback.value = null
    }
}
