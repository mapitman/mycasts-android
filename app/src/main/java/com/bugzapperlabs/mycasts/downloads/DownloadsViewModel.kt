package com.bugzapperlabs.mycasts.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.download.DownloadWorkStatus
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DownloadedEpisodeUiState(
    val item: FeedItem,
    val feedTitle: String,
    val feedImageUrl: String?,
    val isInProgress: Boolean,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val episodes: List<DownloadedEpisodeUiState> = emptyList(),
    val totalBytes: Long = 0L,
    /** Multi-select management, mirroring EpisodeListUiState's pattern -- implicit from a
     *  non-empty selection rather than an explicit toggled flag. */
    val selectedIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/** A single row in the active-download-jobs list (issue #156) -- distinct from
 *  [DownloadedEpisodeUiState], which can't represent a job that's never recorded any progress. */
data class ActiveDownloadUiState(
    val itemId: String,
    val title: String,
    val status: DownloadWorkStatus,
)

/** Unified downloads/episode management screen (issue #69), pairs with #71's auto-cleanup. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val downloadRepository: EnclosureDownloadRepository,
) : ViewModel() {
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DownloadsUiState> = combine(
        feedRepository.observeDownloadedItems(),
        selectedIds,
    ) { episodes, selected ->
        val rows = episodes.map { (item, feedTitle, feedImageUrl) ->
            val isInProgress = item.downloadedFilePath == null
            // downloadedBytes is cleared once a download completes (see
            // FeedItemDao.setDownloadedFilePath), so a completed episode's size comes from the
            // file on disk instead.
            val sizeBytes = if (isInProgress) {
                item.downloadedBytes ?: 0L
            } else {
                item.downloadedFilePath?.let { File(it).length() } ?: 0L
            }
            DownloadedEpisodeUiState(item, feedTitle.orEmpty(), feedImageUrl, isInProgress, sizeBytes)
        }
        // Dropped rather than carried forward (issue #124's pattern) so a stale id for an episode
        // deleted some other way (e.g. an unrelated single-item delete) doesn't keep inflating the
        // top bar's selected count for a row that's no longer even in the list.
        val stillPresent = selected.intersect(rows.map { it.item.id }.toSet())
        DownloadsUiState(episodes = rows, totalBytes = rows.sumOf { it.sizeBytes }, selectedIds = stillPresent)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    // issue #156: surfaces every active download job by itself, including ones stuck retrying
    // that observeDownloadedItems (above) can never see -- it filters on downloadedFilePath/
    // downloadedBytes, both still null for a job that's failed before writing a first byte.
    //
    // Excludes anything already showing up in uiState.episodes above (issue #173 follow-up): once
    // a job has written its first byte, it becomes visible there too (that query matches on
    // downloadedBytes being non-null, not just downloadedFilePath), so without this exclusion the
    // same download showed up twice -- once here, once below -- until the job actually finished and
    // dropped out of observeDownloadWorkInfo. This list is only meant to surface jobs the other one
    // can't see yet, not duplicate ones it already can.
    val activeDownloads: StateFlow<List<ActiveDownloadUiState>> = combine(
        downloadRepository.observeDownloadWorkInfo(),
        feedRepository.observeDownloadedItems(),
    ) { infos, downloaded ->
        val alreadyVisibleIds = downloaded.map { it.item.id }.toSet()
        infos.filterNot { it.itemId in alreadyVisibleIds }.map { info ->
            ActiveDownloadUiState(
                itemId = info.itemId,
                title = feedRepository.getItem(info.itemId)?.title.orEmpty(),
                status = info.status,
            )
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(item: FeedItem) {
        viewModelScope.launch { downloadRepository.deleteDownload(item) }
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

    fun selectAll() {
        selectedIds.value = uiState.value.episodes.map { it.item.id }.toSet()
    }

    /** Deletes (or, for a still-in-progress selected item, cancels -- mirroring [delete]'s own
     *  per-row behavior) every currently-selected episode, then exits selection mode. */
    fun deleteSelected() {
        val ids = selectedIds.value
        val items = uiState.value.episodes.filter { it.item.id in ids }.map { it.item }
        viewModelScope.launch {
            items.forEach { downloadRepository.deleteDownload(it) }
            clearSelection()
        }
    }

    fun cancelAllDownloads() {
        viewModelScope.launch { downloadRepository.cancelAllDownloads() }
    }

    fun cancelDownload(itemId: String) {
        viewModelScope.launch { downloadRepository.cancelDownload(itemId) }
    }
}
