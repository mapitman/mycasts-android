package com.bugzapperlabs.mycasts.feedlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.ui.components.CompactTopBar
import com.bugzapperlabs.mycasts.ui.components.ConfirmDeleteDialog
import com.bugzapperlabs.mycasts.ui.components.ListItemRow
import com.bugzapperlabs.mycasts.ui.components.htmlToPlainText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedListViewModel = hiltViewModel(),
    onFeedClick: (Long) -> Unit = {},
    onAddFeedClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val feedListFontSize by viewModel.feedListFontSize.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val opmlImportResult by viewModel.opmlImportResult.collectAsState()
    val opmlImportProgress by viewModel.opmlImportProgress.collectAsState()
    val showAddDefaultFeedsPrompt by viewModel.showAddDefaultFeedsPrompt.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showAddDefaultFeedsPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddDefaultFeedsPrompt,
            title = { Text(stringResource(R.string.feed_list_add_default_feeds_prompt_title)) },
            text = { Text(stringResource(R.string.feed_list_add_default_feeds_prompt_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::acceptAddDefaultFeedsPrompt) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddDefaultFeedsPrompt) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRefreshError()
        }
    }

    LaunchedEffect(opmlImportResult) {
        opmlImportResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeOpmlImportResult()
        }
    }

    Scaffold(
        modifier = modifier,
        // issue #193: this screen is only ever reached via MainActivity's own bottom
        // NavigationBar, whose outer Scaffold already reserves the navigation-bar inset as this
        // screen's real on-screen bottom edge -- Scaffold's own default (systemBars) reserved that
        // same inset a *second* time here, since this Scaffold has no bottomBar of its own for it
        // to automatically exclude, leaving a blank gap above the real nav bar regardless of
        // whether the mini player strip was showing. The status-bar (top) portion is left alone:
        // CompactTopBar below already occupies real space for it, which Scaffold does correctly
        // account for.
        contentWindowInsets = WindowInsets.systemBars.exclude(WindowInsets.navigationBars),
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            if (uiState.isSelectionMode) {
                // Multi-select management (issue #124), replacing Settings' old "Remove all
                // feeds" -- same selection-mode top bar pattern as EpisodeListScreen.
                TopAppBar(
                    title = { Text(stringResource(R.string.article_list_selected, uiState.selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_selection))
                        }
                    },
                    actions = {
                        // Tapping this again once every feed is already selected deselects them
                        // all instead of being a no-op.
                        val allSelected = uiState.selectedIds.size == uiState.feeds.size
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(
                                if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = stringResource(
                                    if (allSelected) R.string.cd_deselect_all else R.string.cd_select_all,
                                ),
                            )
                        }
                        IconButton(onClick = viewModel::markSelectedRead) {
                            Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.cd_mark_read))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.feed_properties_unsubscribe))
                        }
                    },
                )
            } else {
                // No title (issue #127) and nothing else in this bar either, so a full-height
                // empty TopAppBar is replaced with CompactTopBar, which reserves only the
                // status-bar inset itself rather than Material's ~64dp component height on top.
                CompactTopBar()
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(onClick = onAddFeedClick) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_feed))
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // A background OPML import (issue #271) can finish well after the screen that started
            // it -- Add Feed or a share-intent -- has already closed, so its progress is shown here
            // too (issue #105), above either branch below, since it's just as likely to be running
            // while the feed list is still empty as once feeds already exist.
            opmlImportProgress?.takeIf { it.totalCount > 0 }?.let { progress ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.opml_import_progress, progress.completedCount, progress.totalCount))
                    LinearProgressIndicator(
                        progress = { progress.completedCount.toFloat() / progress.totalCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (uiState.feeds.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text = stringResource(R.string.feed_list_no_feeds_yet),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 24.dp, bottom = 96.dp)
                            .widthIn(max = 220.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.feed_list_empty_hint),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.End,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(40.dp)
                                .rotate(90f),
                        )
                    }
                }
            } else {
                // A taller-than-default pull threshold (issue #45): overshooting while flicking the
                // list to the top was accidentally triggering a refresh-all-feeds. The gesture
                // itself stays -- just requires a more deliberate pull to actually fire.
                val pullToRefreshThreshold = PullToRefreshDefaults.PositionalThreshold * 2
                val pullToRefreshState = rememberPullToRefreshState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pullToRefresh(
                            state = pullToRefreshState,
                            isRefreshing = uiState.isRefreshing,
                            onRefresh = viewModel::refresh,
                            threshold = pullToRefreshThreshold,
                        ),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = stringResource(R.string.feed_list_section_unplayed, uiState.totalUnread),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(uiState.feeds, key = { it.feed.id }) { item ->
                            val isSelected = item.feed.id in uiState.selectedIds
                            ListItemRow(
                                title = item.feed.userTitle ?: item.feed.title.orEmpty(),
                                // issue #167: some feeds embed raw HTML (e.g. <br>, <em>) in their
                                // description -- shown here as a plain one-line subtitle, so it's
                                // stripped to text rather than rendered (PodcastDetailsScreen does
                                // the same for its own description preview).
                                subtitle = item.feed.description?.let { htmlToPlainText(it) },
                                imageUrl = item.feed.imageUrl,
                                unreadCount = item.unreadCount,
                                titleFontScale = feedListFontSize,
                                highlighted = item.hasNewEpisodes,
                                selectionMode = uiState.isSelectionMode,
                                selected = isSelected,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(item.feed.id)
                                    } else {
                                        onFeedClick(item.feed.id)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(item.feed.id) },
                            )
                        }
                    }
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = uiState.isRefreshing,
                        state = pullToRefreshState,
                        threshold = pullToRefreshThreshold,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            itemCount = uiState.selectedIds.size,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteSelected()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
