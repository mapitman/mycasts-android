package com.bugzapperlabs.mycasts.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.download.DownloadWorkStatus
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
)

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
    val uiState: StateFlow<DownloadsUiState> = feedRepository.observeDownloadedItems()
        .map { episodes ->
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
            DownloadsUiState(episodes = rows, totalBytes = rows.sumOf { it.sizeBytes })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    // issue #156: surfaces every active download job by itself, including ones stuck retrying
    // that observeDownloadedItems (above) can never see -- it filters on downloadedFilePath/
    // downloadedBytes, both still null for a job that's failed before writing a first byte.
    val activeDownloads: StateFlow<List<ActiveDownloadUiState>> = downloadRepository.observeDownloadWorkInfo()
        .map { infos ->
            infos.map { info ->
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

    fun cancelAllDownloads() {
        viewModelScope.launch { downloadRepository.cancelAllDownloads() }
    }

    fun cancelDownload(itemId: String) {
        viewModelScope.launch { downloadRepository.cancelDownload(itemId) }
    }
}
