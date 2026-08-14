package com.bugzapperlabs.mycasts.feedlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.ui.components.CompactTopBar
import com.bugzapperlabs.mycasts.ui.components.ListItemRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedListViewModel = hiltViewModel(),
    onFeedClick: (Long) -> Unit = {},
    onAddFeedClick: () -> Unit = {},
    onFeedLongClick: (Long) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val feedListFontSize by viewModel.feedListFontSize.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val opmlImportResult by viewModel.opmlImportResult.collectAsState()
    val opmlImportProgress by viewModel.opmlImportProgress.collectAsState()
    val showAddDefaultFeedsPrompt by viewModel.showAddDefaultFeedsPrompt.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        // No title (issue #127) and nothing else in this bar either, so a full-height empty
        // TopAppBar is replaced with CompactTopBar, which reserves only the status-bar inset
        // itself rather than Material's ~64dp component height on top of it.
        topBar = { CompactTopBar() },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFeedClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_feed))
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
                            ListItemRow(
                                title = item.feed.userTitle ?: item.feed.title.orEmpty(),
                                subtitle = item.feed.description,
                                imageUrl = item.feed.imageUrl,
                                unreadCount = item.unreadCount,
                                titleFontScale = feedListFontSize,
                                onClick = { onFeedClick(item.feed.id) },
                                onLongClick = { onFeedLongClick(item.feed.id) },
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
}
