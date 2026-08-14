package com.bugzapperlabs.mycasts.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CancelPresentation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.download.DownloadWorkStatus
import com.bugzapperlabs.mycasts.ui.components.CompactTopBar
import com.bugzapperlabs.mycasts.ui.components.ConfirmDeleteDialog
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    var pendingDelete by remember { mutableStateOf<FeedItem?>(null) }
    var pendingCancelAll by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            // No "Downloads" label (issue #127): the bottom nav's highlighted tab already conveys
            // that. The byte total is real content, not a redundant screen name, so it stays.
            // No back arrow either (issue #128) -- this is a bottom-nav destination. CompactTopBar
            // replaces TopAppBar here too, sized to just this text rather than Material's taller
            // ~64dp component height on top of it.
            CompactTopBar {
                Text(
                    text = formatBytes(uiState.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                // Always shown, not gated on activeDownloads being non-empty (issue #156): a job
                // enqueued before per-item tagging existed has no way to recover its itemId, so it
                // can never appear in that list even though cancelAllDownloads still reaches it via
                // WorkManager's always-present per-worker-class tag -- gating this on the list
                // would hide the one button able to clear exactly that kind of stuck job.
                IconButton(onClick = { pendingCancelAll = true }) {
                    Icon(Icons.Filled.CancelPresentation, contentDescription = stringResource(R.string.cd_cancel_all_downloads))
                }
            }
        },
    ) { innerPadding ->
        if (uiState.episodes.isEmpty() && activeDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_empty))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (activeDownloads.isNotEmpty()) {
                    item(key = "active-header") {
                        Text(
                            text = stringResource(R.string.downloads_active_section_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    // A job stuck retrying after failing before writing any bytes (issue #156)
                    // never appears below in the completed/in-progress-with-bytes list -- it's
                    // invisible to observeDownloadedItems, which filters on downloadedFilePath/
                    // downloadedBytes, both still null for it. This is queried straight from
                    // WorkManager instead, so every active job shows up regardless.
                    items(activeDownloads, key = { "active-${it.itemId}" }) { active ->
                        ActiveDownloadRow(active = active, onCancel = { viewModel.cancelDownload(active.itemId) })
                    }
                }
                items(uiState.episodes, key = { it.item.id }) { episode ->
                    DownloadedEpisodeRow(episode = episode, onDelete = { pendingDelete = episode.item })
                }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDeleteDialog(
            itemCount = 1,
            onConfirm = {
                viewModel.delete(item)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (pendingCancelAll) {
        AlertDialog(
            onDismissRequest = { pendingCancelAll = false },
            title = { Text(stringResource(R.string.downloads_cancel_all_title)) },
            text = { Text(stringResource(R.string.downloads_cancel_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelAllDownloads()
                    pendingCancelAll = false
                }) {
                    Text(stringResource(R.string.downloads_cancel_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancelAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadedEpisodeRow(episode: DownloadedEpisodeUiState, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A placeholder fills the same slot when there's no artwork (issue #149), matching
            // ListItemRow's leading-image treatment used in the feed and episode lists.
            if (episode.feedImageUrl != null) {
                AsyncImage(
                    model = episode.feedImageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.RssFeed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = episode.item.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.downloads_item_subtitle, episode.feedTitle, formatBytes(episode.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.isInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete_download))
            }
        }
        if (episode.isInProgress) {
            val progress = episode.item.enclosureLength?.takeIf { it > 0 }
                ?.let { (episode.sizeBytes.toFloat() / it).coerceIn(0f, 1f) }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActiveDownloadRow(active: ActiveDownloadUiState, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = active.title.ifBlank { stringResource(R.string.notification_download_title_fallback) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(active.status.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel_download))
        }
    }
}

private fun DownloadWorkStatus.labelRes(): Int = when (this) {
    DownloadWorkStatus.QUEUED -> R.string.downloads_status_queued
    DownloadWorkStatus.RUNNING -> R.string.downloads_status_running
    DownloadWorkStatus.RETRYING -> R.string.downloads_status_retrying
    DownloadWorkStatus.BLOCKED -> R.string.downloads_status_blocked
}

@Composable
private fun formatBytes(bytes: Long): String {
    val units = listOf(
        stringResource(R.string.unit_bytes),
        stringResource(R.string.unit_kilobytes),
        stringResource(R.string.unit_megabytes),
        stringResource(R.string.unit_gigabytes),
    )
    if (bytes <= 0) return "0 ${units[0]}"
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / 1024.0.pow(digitGroups), units[digitGroups])
}
