package com.bugzapperlabs.myfeeds.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
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
    val isInProgress: Boolean,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val episodes: List<DownloadedEpisodeUiState> = emptyList(),
    val totalBytes: Long = 0L,
)

/** Unified downloads/episode management screen (issue #69), pairs with #71's auto-cleanup. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val downloadRepository: EnclosureDownloadRepository,
) : ViewModel() {
    val uiState: StateFlow<DownloadsUiState> = feedRepository.observeDownloadedItems()
        .map { episodes ->
            val rows = episodes.map { (item, feedTitle) ->
                val isInProgress = item.downloadedFilePath == null
                // downloadedBytes is cleared once a download completes (see
                // FeedItemDao.setDownloadedFilePath), so a completed episode's size comes from the
                // file on disk instead.
                val sizeBytes = if (isInProgress) {
                    item.downloadedBytes ?: 0L
                } else {
                    item.downloadedFilePath?.let { File(it).length() } ?: 0L
                }
                DownloadedEpisodeUiState(item, feedTitle.orEmpty(), isInProgress, sizeBytes)
            }
            DownloadsUiState(episodes = rows, totalBytes = rows.sumOf { it.sizeBytes })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun delete(item: FeedItem) {
        viewModelScope.launch { downloadRepository.deleteDownload(item) }
    }
}
