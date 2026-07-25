package com.bugzapperlabs.myfeeds.feedlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.DefaultFeedsSeeder
import com.bugzapperlabs.myfeeds.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateResult
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.refresh.FeedRefreshState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedListItemUiState(val feed: Feed, val unreadCount: Int)

enum class FeedListSection { PODCASTS, FEEDS }

data class FeedListSectionUiState(
    val section: FeedListSection,
    val feeds: List<FeedListItemUiState>,
) {
    val totalUnread: Int get() = feeds.sumOf { it.unreadCount }
}

data class FeedListUiState(
    val sections: List<FeedListSectionUiState> = emptyList(),
    val totalUnread: Int = 0,
    val isRefreshing: Boolean = false,
)

private data class FeedListSourceData(
    val feeds: List<Feed>,
    val unreadCounts: Map<Long, Int>,
    val totalUnread: Int,
    val refreshing: Boolean,
)

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val seeder: DefaultFeedsSeeder,
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer,
    private val feedRefreshState: FeedRefreshState,
    settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    // Shared app-wide signal (issue #152), not ViewModel-local -- a scheduled background refresh
    // (FeedRefreshWorker) writes to the same DB this screen observes, so counts need to freeze
    // for that too, not just a manual pull-to-refresh initiated from here.
    private val isRefreshing = feedRefreshState.isRefreshing
    private val _refreshError = MutableStateFlow<String?>(null)

    /** One-shot refresh-failure message for a Snackbar; cleared via [consumeRefreshError]. */
    val refreshError: StateFlow<String?> = _refreshError

    // Holds the last snapshot taken while NOT refreshing (issue #152): a refresh inserts/evicts
    // items one feed at a time, so reacting to every intermediate write made unread counts
    // visibly rise then fall mid-refresh instead of settling once, atomically, when it's actually
    // done. `isRefreshing` itself is exposed live (below) so the spinner still responds instantly;
    // only the counts/sections freeze. The moment a refresh finishes, this combine re-emits with
    // whatever the DB currently holds -- already fully trimmed, since persistence happens
    // synchronously before `isRefreshing` flips back to false in `refresh()` -- so the UI jumps
    // straight to the correct settled numbers in one step rather than trickling there.
    private val stableSource = MutableStateFlow(FeedListSourceData(emptyList(), emptyMap(), 0, false))

    val uiState: StateFlow<FeedListUiState> = combine(
        stableSource,
        feedRepository.observePodcastFeedIds(),
        isRefreshing,
    ) { source, podcastFeedIds, refreshing ->
        // Podcast-ness (issue #65) splits the flat feed list into two fixed sections (issue #118):
        // "Podcasts" (feeds with at least one audio-enclosure item) and "Feeds" (everything else).
        val podcastFeeds = source.feeds
            .filter { it.id in podcastFeedIds }
            .map { feed -> FeedListItemUiState(feed, source.unreadCounts[feed.id] ?: 0) }
        val otherFeeds = source.feeds
            .filterNot { it.id in podcastFeedIds }
            .map { feed -> FeedListItemUiState(feed, source.unreadCounts[feed.id] ?: 0) }
        FeedListUiState(
            sections = listOf(
                FeedListSectionUiState(FeedListSection.PODCASTS, podcastFeeds),
                FeedListSectionUiState(FeedListSection.FEEDS, otherFeeds),
            ),
            totalUnread = source.totalUnread,
            isRefreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedListUiState())

    init {
        viewModelScope.launch { seeder.seedIfFirstRun() }
        viewModelScope.launch {
            combine(
                feedRepository.observeAllFeeds(),
                feedRepository.observeUnreadCountsByFeed(),
                feedRepository.observeTotalUnreadCount(),
                isRefreshing,
            ) { feeds, unreadCounts, totalUnread, refreshing ->
                FeedListSourceData(feeds, unreadCounts, totalUnread, refreshing)
            }.collect { source -> if (!source.refreshing) stableSource.value = source }
        }
    }

    val feedListFontSize: StateFlow<FontSize> = settingsDataStore.settings
        .map { it.feedListFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.LARGE)

    fun refresh() {
        viewModelScope.launch {
            feedRefreshState.track {
                val feeds = feedRepository.observeAllFeeds().first()
                val results = feedUpdateEngine.updateFeeds(feeds)
                autoQueueAndDownloadEnforcer.apply(results)
                if (results.any { it is FeedUpdateResult.Failure }) {
                    _refreshError.value = context.getString(R.string.feed_list_refresh_error)
                }
            }
        }
    }

    fun consumeRefreshError() {
        _refreshError.value = null
    }
}
