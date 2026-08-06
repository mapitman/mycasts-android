package com.bugzapperlabs.myfeeds.feedriver

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.articlelist.ArticleDateFormatter
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.settings.scaleFactor
import com.bugzapperlabs.myfeeds.ui.components.SwipeToToggleReadBox

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedRiverScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedRiverViewModel = hiltViewModel(),
    onArticleClick: (feedId: Long, itemId: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onQueueClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val listFontSize by viewModel.listFontSize.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val queueFeedback by viewModel.queueFeedback.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRefreshError()
        }
    }

    LaunchedEffect(queueFeedback) {
        queueFeedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeQueueFeedback()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.article_list_selected, uiState.selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.cd_select_all))
                        }
                        IconButton(onClick = { viewModel.markSelectedRead(true) }) {
                            Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.cd_mark_read))
                        }
                        IconButton(onClick = { viewModel.markSelectedRead(false) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_mark_unread))
                        }
                        IconButton(onClick = viewModel::addSelectedToQueue, enabled = uiState.canAddSelectedToQueue) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = stringResource(R.string.cd_add_to_queue))
                        }
                        IconButton(onClick = viewModel::deleteSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.feed_list_feeds_tab))
                            Text(
                                text = stringResource(R.string.article_list_unread_count, uiState.unreadCount),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onQueueClick) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.cd_open_queue))
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            val selectedTab = if (uiState.showUnreadOnly) 0 else 1
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { viewModel.setShowUnreadOnly(true) },
                    text = { Text(stringResource(R.string.article_list_tab_unread)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setShowUnreadOnly(false) },
                    text = { Text(stringResource(R.string.article_list_tab_all)) },
                )
            }
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (uiState.articles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (uiState.showUnreadOnly) R.string.article_list_no_unread_articles else R.string.article_list_no_articles,
                            ),
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.articles, key = { it.id }) { article ->
                            // Selection mode already has its own per-row tap target (the
                            // checkbox); swiping to toggle read state there would fight with it.
                            if (uiState.isSelectionMode) {
                                FeedRiverArticleRow(
                                    article = article,
                                    feedTitle = uiState.feedTitles[article.feedId].orEmpty(),
                                    selected = article.id in uiState.selectedIds,
                                    selectionMode = true,
                                    titleFontScale = listFontSize.scaleFactor,
                                    onClick = { viewModel.toggleSelection(article.id) },
                                    onLongClick = { viewModel.toggleSelection(article.id) },
                                    onAddToQueue = { viewModel.addToQueue(article.id) },
                                )
                            } else {
                                SwipeToToggleReadBox(
                                    isRead = article.isRead,
                                    onToggleRead = { viewModel.toggleRead(article) },
                                ) {
                                    FeedRiverArticleRow(
                                        article = article,
                                        feedTitle = uiState.feedTitles[article.feedId].orEmpty(),
                                        selected = false,
                                        selectionMode = false,
                                        titleFontScale = listFontSize.scaleFactor,
                                        onClick = { onArticleClick(article.feedId, article.id) },
                                        onLongClick = { viewModel.toggleSelection(article.id) },
                                        onAddToQueue = { viewModel.addToQueue(article.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRiverArticleRow(
    article: FeedItem,
    feedTitle: String,
    selected: Boolean,
    selectionMode: Boolean,
    titleFontScale: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    val isRowSelected = selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selectionMode) Modifier.semantics { this.selected = isRowSelected } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = article.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium.let {
                    if (titleFontScale == 1f) it else it.copy(fontSize = it.fontSize * titleFontScale)
                },
                fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.Bold,
                color = if (article.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = stringResource(R.string.feed_river_item_source, feedTitle, ArticleDateFormatter.format(article.publishDate)),
                style = MaterialTheme.typography.bodySmall,
                color = if (article.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        if (!selectionMode && article.isPodcastEpisode) {
            IconButton(onClick = onAddToQueue) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = stringResource(R.string.cd_add_to_queue))
            }
        }
    }
}
