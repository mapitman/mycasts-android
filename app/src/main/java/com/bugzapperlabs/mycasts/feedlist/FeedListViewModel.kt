package com.bugzapperlabs.mycasts.feedlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateResult
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.opml.ImportProgress
import com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator
import com.bugzapperlabs.mycasts.data.opml.OpmlParser
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.FONT_SCALE_DEFAULT
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.refresh.FeedRefreshState
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

data class FeedListUiState(
    val feeds: List<FeedListItemUiState> = emptyList(),
    val totalUnread: Int = 0,
    val isRefreshing: Boolean = false,
    /** Multi-select management (issue #124). Unlike EpisodeListUiState's selection mode (implicit
     *  from a non-empty selection), this is tracked explicitly: deselecting everything via
     *  [FeedListViewModel.selectAll]'s toggle should uncheck every row without also kicking the
     *  user back out of selection mode -- only [FeedListViewModel.clearSelection] (the top bar's
     *  close icon) does that. */
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
)

private data class FeedListSourceData(
    val feeds: List<Feed>,
    val unreadCounts: Map<Long, Int>,
    val totalUnread: Int,
    val refreshing: Boolean,
)

@HiltViewModel
class FeedListViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer,
    private val feedRefreshState: FeedRefreshState,
    private val opmlImportCoordinator: OpmlImportCoordinator,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    // Shared app-wide signal (issue #152), not ViewModel-local -- a scheduled background refresh
    // (FeedRefreshWorker) writes to the same DB this screen observes, so counts need to freeze
    // for that too, not just a manual pull-to-refresh initiated from here.
    private val isRefreshing = feedRefreshState.isRefreshing
    private val _refreshError = MutableStateFlow<String?>(null)

    /** One-shot refresh-failure message for a Snackbar; cleared via [consumeRefreshError]. */
    val refreshError: StateFlow<String?> = _refreshError

    // A background OPML import (issue #271) can finish well after the Add Feed screen that
    // started it has already closed, so its result is surfaced here instead, on the screen the
    // user lands back on.
    val opmlImportResult: StateFlow<String?> = opmlImportCoordinator.result

    /** Live completed/total counts while that same import runs (issue #105). */
    val opmlImportProgress: StateFlow<ImportProgress?> = opmlImportCoordinator.progress

    fun consumeOpmlImportResult() {
        opmlImportCoordinator.consumeResult()
    }

    // Gates on: no feeds yet, never shown before, and not already mid-import (issue #271's
    // coordinator can still be running an OPML-file import when this screen first loads empty).
    val showAddDefaultFeedsPrompt: StateFlow<Boolean> = combine(
        feedRepository.observeAllFeeds(),
        settingsDataStore.settings,
        opmlImportCoordinator.progress,
    ) { feeds, settings, progress ->
        feeds.isEmpty() && !settings.addDefaultFeedsPromptShown && progress == null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun acceptAddDefaultFeedsPrompt() {
        viewModelScope.launch {
            settingsDataStore.setAddDefaultFeedsPromptShown(true)
            // Same parse-then-import sequence as SettingsViewModel.addDefaultFeeds().
            val document = try {
                context.assets.open("default_feeds.opml").use { OpmlParser.parse(it) }
            } catch (_: Exception) {
                null
            }
            if (document != null) opmlImportCoordinator.startImport(document)
        }
    }

    fun dismissAddDefaultFeedsPrompt() {
        viewModelScope.launch { settingsDataStore.setAddDefaultFeedsPromptShown(true) }
    }

    // Holds the last snapshot taken while NOT refreshing (issue #152): a refresh inserts/evicts
    // items one feed at a time, so reacting to every intermediate write made unread counts
    // visibly rise then fall mid-refresh instead of settling once, atomically, when it's actually
    // done. `isRefreshing` itself is exposed live (below) so the spinner still responds instantly;
    // only the counts/sections freeze. The moment a refresh finishes, this combine re-emits with
    // whatever the DB currently holds -- already fully trimmed, since persistence happens
    // synchronously before `isRefreshing` flips back to false in `refresh()` -- so the UI jumps
    // straight to the correct settled numbers in one step rather than trickling there.
    private val stableSource = MutableStateFlow(FeedListSourceData(emptyList(), emptyMap(), 0, false))

    private val isSelectionModeActive = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<FeedListUiState> = combine(
        stableSource,
        isRefreshing,
        isSelectionModeActive,
        selectedIds,
    ) { source, refreshing, selectionMode, selected ->
        FeedListUiState(
            feeds = source.feeds.map { feed -> FeedListItemUiState(feed, source.unreadCounts[feed.id] ?: 0) },
            totalUnread = source.totalUnread,
            isRefreshing = refreshing,
            isSelectionMode = selectionMode,
            selectedIds = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedListUiState())

    init {
        viewModelScope.launch {
            // Whether stableSource has ever been populated yet (issue #276): a scheduled refresh
            // (e.g. FeedRefreshWorker firing right at launch) can already be running by the time
            // this collector's very first emission arrives, before any real snapshot has ever been
            // stored -- unconditionally requiring `!refreshing` then left stableSource stuck at its
            // empty default, rendering the feed list as blank/empty-state for the whole refresh
            // instead of showing whatever's already in the DB. The first emission is let through
            // regardless of `refreshing` so cold start always shows current data immediately; only
            // *subsequent* emissions freeze while a refresh is in flight, preserving #152's
            // original anti-flicker behavior for refreshes triggered after the list is populated.
            var hasEmittedInitialSnapshot = false
            var wasRefreshing = false
            combine(
                feedRepository.observeAllFeeds(),
                feedRepository.observeUnreadCountsByFeed(),
                feedRepository.observeTotalUnreadCount(),
                isRefreshing,
            ) { feeds, unreadCounts, totalUnread, refreshing ->
                FeedListSourceData(feeds, unreadCounts, totalUnread, refreshing)
            }.collect { source ->
                // Checked directly against feedRefreshState rather than trusting this combine
                // tuple's own `refreshing` field (issue #76 follow-up): combine() collects each
                // upstream flow via its own independently-scheduled child coroutine, so under load
                // the feeds/counts branch can be processed before the isRefreshing branch has
                // caught up, letting a DB write that lands mid-refresh be paired with a stale
                // "not refreshing" and leak through the freeze. activeCount's raw `.value` has no
                // such lag -- it's a plain field read, always immediately current.
                val actuallyRefreshing = feedRefreshState.isCurrentlyRefreshing()
                if (!actuallyRefreshing || !hasEmittedInitialSnapshot) {
                    // The falling edge of `refreshing` (issue #76) can't just trust this combine
                    // tuple's feeds/counts even though it's now unfrozen -- Room's
                    // invalidation-triggered re-query for whichever refresh just finished runs on
                    // its own background thread with no guarantee it's landed by the instant this
                    // particular combine tuple was produced. An explicit, synchronous re-read
                    // guarantees the settled snapshot is actually current.
                    stableSource.value = if (wasRefreshing && !actuallyRefreshing) captureCurrentSnapshot() else source
                    hasEmittedInitialSnapshot = true
                }
                wasRefreshing = actuallyRefreshing
            }
        }
    }

    private suspend fun captureCurrentSnapshot(): FeedListSourceData = FeedListSourceData(
        feeds = feedRepository.observeAllFeeds().first(),
        unreadCounts = feedRepository.observeUnreadCountsByFeed().first(),
        totalUnread = feedRepository.observeTotalUnreadCount().first(),
        refreshing = false,
    )

    val feedListFontSize: StateFlow<Float> = settingsDataStore.settings
        .map { it.fontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FONT_SCALE_DEFAULT)

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

    // Multi-select management (issue #124), replacing Settings' old blunt "Remove all feeds"
    // with per-feed selection here.
    fun toggleSelection(feedId: Long) {
        isSelectionModeActive.value = true
        selectedIds.value = if (feedId in selectedIds.value) {
            selectedIds.value - feedId
        } else {
            selectedIds.value + feedId
        }
    }

    /** The only path back to the non-selection top bar/FAB -- unlike [selectAll]'s toggle, which
     *  only ever empties the selection, leaving selection mode itself active. */
    fun clearSelection() {
        isSelectionModeActive.value = false
        selectedIds.value = emptySet()
    }

    /** Toggles between everything selected and nothing selected, rather than only ever selecting
     *  everything -- tapping it a second time once the whole list is already selected unchecks
     *  every row without leaving selection mode (that's [clearSelection]'s job, via the top bar's
     *  close icon). */
    fun selectAll() {
        val allIds = uiState.value.feeds.map { it.feed.id }.toSet()
        isSelectionModeActive.value = true
        selectedIds.value = if (selectedIds.value == allIds) emptySet() else allIds
    }

    fun markSelectedRead() {
        val ids = selectedIds.value
        viewModelScope.launch {
            ids.forEach { feedRepository.markAllRead(it) }
            clearSelection()
        }
    }

    fun deleteSelected() {
        val feeds = uiState.value.feeds.filter { it.feed.id in selectedIds.value }.map { it.feed }
        viewModelScope.launch {
            feeds.forEach { feedRepository.unsubscribe(it) }
            clearSelection()
        }
    }
}
