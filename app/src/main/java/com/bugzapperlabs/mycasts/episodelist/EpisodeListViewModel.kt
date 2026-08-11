package com.bugzapperlabs.mycasts.episodelist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateResult
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.FontSize
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
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

data class EpisodeListUiState(
    val feedTitle: String = "",
    val showUnreadOnly: Boolean = true,
    val episodes: List<FeedItem> = emptyList(),
    val unreadCount: Int = 0,
    val selectedIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    /** Item IDs currently in the Next Up queue (issue #52), so the add-to-queue button can show a
     *  different icon for episodes already queued. */
    val queuedIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

private data class EpisodeListSourceData(
    val feedTitle: String,
    val episodes: List<FeedItem>,
    val unreadCount: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EpisodeListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer,
    private val queueRepository: QueueRepository,
    private val downloadRepository: EnclosureDownloadRepository,
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
    private val _downloadFeedback = MutableStateFlow<String?>(null)

    /** One-shot refresh-failure message for a Snackbar; cleared via [consumeRefreshError]. */
    val refreshError: StateFlow<String?> = _refreshError

    /** One-shot add-to-queue confirmation for a Snackbar (issue #126); cleared via [consumeQueueFeedback]. */
    val queueFeedback: StateFlow<String?> = _queueFeedback

    /** One-shot bulk-download confirmation for a Snackbar (issue #42); cleared via [consumeDownloadFeedback]. */
    val downloadFeedback: StateFlow<String?> = _downloadFeedback

    // Holds the last snapshot taken while NOT refreshing (issue #73, mirroring issue #152's fix
    // for the feed list): a refresh inserts/dedupes/evicts items one at a time, so reacting to
    // every intermediate write made the unread count and episode list visibly rise then fall
    // mid-refresh -- e.g. climbing well past a 20-episode limit before resting back at 20 -- instead
    // of settling once, atomically, when the refresh is actually done. `isRefreshing` itself is
    // exposed live (below) so the spinner still responds instantly; only episodes/unreadCount
    // freeze. `showUnreadOnly` is also live, not frozen, since it's user-driven tab selection, not
    // refresh-driven data.
    private val stableSource = MutableStateFlow(EpisodeListSourceData("", emptyList(), 0))

    val uiState: StateFlow<EpisodeListUiState> = combine(
        stableSource,
        showUnreadOnly,
        selectedIds,
        isRefreshing,
        queueRepository.observeQueuedItemIds(),
    ) { source, unreadOnly, selected, refreshing, queuedIds ->
        EpisodeListUiState(
            feedTitle = source.feedTitle,
            showUnreadOnly = unreadOnly,
            episodes = source.episodes,
            unreadCount = source.unreadCount,
            selectedIds = selected,
            isRefreshing = refreshing,
            queuedIds = queuedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeListUiState())

    // Guards the init block below from clobbering an explicit setShowUnreadOnly() call that
    // arrives before settingsDataStore.settings.first() resolves (issue #215) -- if that read
    // requires a real suspension (e.g. a cold DataStore file read) rather than completing
    // synchronously, the caller's explicit choice can otherwise be silently overwritten once the
    // deferred default-loading coroutine finally runs.
    private var showUnreadOnlyExplicitlySet = false

    init {
        viewModelScope.launch {
            val defaultToAllView = settingsDataStore.settings.first().defaultToAllItemsView
            if (!showUnreadOnlyExplicitlySet) {
                showUnreadOnly.value = !defaultToAllView
            }

            val feed = feedRepository.getFeed(feedId)
            feedTitle.value = feed?.userTitle ?: feed?.title.orEmpty()

            // Whether stableSource has ever been populated yet (issue #276 parity): a scheduled
            // refresh (e.g. FeedRefreshWorker firing right as this screen opens) can already be
            // running by the time this collector's very first emission arrives, before any real
            // snapshot has ever been stored -- unconditionally requiring `!refreshing` then left
            // stableSource stuck at its empty default, rendering the screen blank/empty-state for
            // the whole refresh instead of showing whatever's already in the DB. The first
            // emission is let through regardless of `refreshing` so cold start always shows
            // current data immediately; only *subsequent* emissions freeze while a refresh is in
            // flight.
            var hasEmittedInitialSnapshot = false
            combine(
                feedTitle,
                showUnreadOnly.flatMapLatest { unreadOnly ->
                    if (unreadOnly) feedRepository.observeUnreadItems(feedId) else feedRepository.observeItems(feedId)
                },
                feedRepository.observeUnreadCount(feedId),
                isRefreshing,
            ) { title, episodes, unreadCount, refreshing ->
                EpisodeListSourceData(title, episodes, unreadCount) to refreshing
            }.collect { (source, refreshing) ->
                if (!refreshing || !hasEmittedInitialSnapshot) {
                    stableSource.value = source
                    hasEmittedInitialSnapshot = true
                }
            }
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
            // Captured explicitly rather than trusting the init block's reactive combine to have
            // already caught up (issue #73): Room's invalidation-triggered re-query for the
            // just-finished insert/trim runs on its own background thread, with no guarantee it's
            // landed by the instant this coroutine reaches this line, so isRefreshing flipping to
            // false could otherwise unfreeze stableSource onto a stale, not-yet-trimmed count for a
            // frame before the real query result arrives a moment later.
            stableSource.value = captureCurrentSnapshot()
            isRefreshing.value = false
        }
    }

    private suspend fun captureCurrentSnapshot(): EpisodeListSourceData {
        val episodes = if (showUnreadOnly.value) {
            feedRepository.observeUnreadItems(feedId).first()
        } else {
            feedRepository.observeItems(feedId).first()
        }
        val unreadCount = feedRepository.observeUnreadCount(feedId).first()
        return EpisodeListSourceData(feedTitle.value, episodes, unreadCount)
    }

    fun consumeRefreshError() {
        _refreshError.value = null
    }

    val episodeListFontSize: StateFlow<FontSize> = settingsDataStore.settings
        .map { it.episodeListFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontSize.NORMAL)

    fun setShowUnreadOnly(unreadOnly: Boolean) {
        showUnreadOnlyExplicitlySet = true
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

    /** Selects every currently-visible/filtered episode (issue #72). */
    fun selectAll() {
        selectedIds.value = uiState.value.episodes.map { it.id }.toSet()
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

    /**
     * Deletes each selected episode's *download*, not the episode itself (issue #54) -- episodes
     * with no download among the selection are silently skipped, mirroring how
     * [downloadSelected] skips episodes already downloaded, in reverse.
     */
    fun deleteSelected() {
        val ids = selectedIds.value
        viewModelScope.launch {
            val eligible = uiState.value.episodes.filter {
                it.id in ids && (it.downloadedFilePath != null || it.downloadedBytes != null)
            }
            eligible.forEach { downloadRepository.deleteDownload(it) }
            _downloadFeedback.value = when (eligible.size) {
                0 -> context.getString(R.string.download_feedback_nothing_to_delete)
                1 -> context.getString(R.string.download_feedback_deleted)
                else -> context.getString(R.string.download_feedback_deleted_multiple, eligible.size)
            }
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
     * podcast-specific, so non-episode items in the selection are silently skipped.
     */
    fun addSelectedToQueue() {
        val ids = selectedIds.value
        viewModelScope.launch {
            val episodeIds = uiState.value.episodes.filter { it.id in ids && it.isPodcastEpisode }.map { it.id }
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

    /**
     * Starts downloading every selected podcast episode that isn't already downloaded or
     * downloading (issue #42). Selection mode isn't podcast-specific, so non-episode items in the
     * selection are silently skipped, mirroring [addSelectedToQueue].
     */
    fun downloadSelected() {
        val ids = selectedIds.value
        viewModelScope.launch {
            val eligible = uiState.value.episodes.filter {
                it.id in ids && it.isPodcastEpisode && it.downloadedFilePath == null && it.downloadedBytes == null
            }
            eligible.forEach { downloadRepository.startDownload(it) }
            _downloadFeedback.value = when (eligible.size) {
                0 -> context.getString(R.string.download_feedback_already_downloaded)
                1 -> context.getString(R.string.download_feedback_started)
                else -> context.getString(R.string.download_feedback_started_multiple, eligible.size)
            }
            clearSelection()
        }
    }

    fun consumeDownloadFeedback() {
        _downloadFeedback.value = null
    }
}
