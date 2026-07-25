package com.bugzapperlabs.myfeeds.feedriver

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateResult
import com.bugzapperlabs.myfeeds.data.local.FeedItem
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedRiverUiState(
    val showUnreadOnly: Boolean = true,
    val articles: List<FeedItem> = emptyList(),
    val feedTitles: Map<Long, String> = emptyMap(),
    val unreadCount: Int = 0,
    val selectedIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * Combined reading view for the "Feeds" list (issue #118): every non-podcast feed's articles
 * merged into one river sorted by publish date, so unrelated feeds can be read through together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedRiverViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val queueRepository: QueueRepository,
    settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val showUnreadOnly = MutableStateFlow(true)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshing = MutableStateFlow(false)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _queueFeedback = MutableStateFlow<String?>(null)

    /** One-shot refresh-failure message for a Snackbar; cleared via [consumeRefreshError]. */
    val refreshError: StateFlow<String?> = _refreshError

    /** One-shot add-to-queue confirmation for a Snackbar (issue #126); cleared via [consumeQueueFeedback]. */
    val queueFeedback: StateFlow<String?> = _queueFeedback

    private val feedIds: StateFlow<List<Long>> = combine(
        feedRepository.observeAllFeeds(),
        feedRepository.observePodcastFeedIds(),
    ) { feeds, podcastFeedIds ->
        feeds.filterNot { it.id in podcastFeedIds }.map { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val feedTitles: StateFlow<Map<Long, String>> = feedRepository.observeAllFeeds()
        .map { feeds -> feeds.associate { it.id to (it.userTitle ?: it.title.orEmpty()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val uiState: StateFlow<FeedRiverUiState> = combine(
        showUnreadOnly,
        feedIds.flatMapLatest { ids -> observeArticles(ids) },
        feedIds.flatMapLatest { ids -> if (ids.isEmpty()) flowOf(0) else feedRepository.observeUnreadCount(ids) },
        feedTitles,
        selectedIds,
    ) { unreadOnly, articles, unreadCount, titles, selected ->
        FeedRiverUiState(unreadOnly, articles, titles, unreadCount, selected)
    }.combine(isRefreshing) { state, refreshing ->
        state.copy(isRefreshing = refreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedRiverUiState())

    private fun observeArticles(ids: List<Long>) = showUnreadOnly.flatMapLatest { unreadOnly ->
        when {
            ids.isEmpty() -> flowOf(emptyList())
            unreadOnly -> feedRepository.observeUnreadItems(ids)
            else -> feedRepository.observeItems(ids)
        }
    }

    val listFontSize: StateFlow<FontSize> = settingsDataStore.settings
        .map { it.listFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.NORMAL)

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            val feeds = feedRepository.observeAllFeeds().first().filter { it.id in feedIds.value }
            val results = feedUpdateEngine.updateFeeds(feeds)
            if (results.any { it is FeedUpdateResult.Failure }) {
                _refreshError.value = context.getString(R.string.feed_list_refresh_error)
            }
            isRefreshing.value = false
        }
    }

    fun consumeRefreshError() {
        _refreshError.value = null
    }

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

    fun consumeQueueFeedback() {
        _queueFeedback.value = null
    }
}
