package com.bugzapperlabs.myfeeds.feedlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.settings.scaleFactor
import com.bugzapperlabs.myfeeds.ui.components.ListItemRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedListViewModel = hiltViewModel(),
    onFeedClick: (Long) -> Unit = {},
    onAddFeedClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onQueueClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onFeedLongClick: (Long) -> Unit = {},
    onReadAllFeedsClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val feedListFontSize by viewModel.feedListFontSize.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val opmlImportResult by viewModel.opmlImportResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = stringResource(R.string.feed_list_total_unread, uiState.totalUnread),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onQueueClick) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.cd_open_queue))
                    }
                    IconButton(onClick = onDownloadsClick) {
                        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.cd_open_downloads))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFeedClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_feed))
            }
        },
    ) { innerPadding ->
        if (uiState.sections.all { it.feeds.isEmpty() }) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
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
            return@Scaffold
        }

        val pagerState = rememberPagerState(pageCount = { uiState.sections.size })
        val scope = rememberCoroutineScope()

        Column(modifier = Modifier.padding(innerPadding)) {
            ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
                uiState.sections.forEachIndexed { index, section ->
                    val tabTitle = when (section.section) {
                        FeedListSection.PODCASTS -> stringResource(R.string.feed_list_podcasts_tab)
                        FeedListSection.FEEDS -> stringResource(R.string.feed_list_feeds_tab)
                    }
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(tabTitle) },
                    )
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val section = uiState.sections[page]
                FeedSectionList(
                    section = section,
                    isRefreshing = uiState.isRefreshing,
                    titleFontScale = feedListFontSize.scaleFactor,
                    onRefresh = viewModel::refresh,
                    onFeedClick = onFeedClick,
                    onFeedLongClick = onFeedLongClick,
                    onReadAllClick = if (section.section == FeedListSection.FEEDS) onReadAllFeedsClick else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSectionList(
    section: FeedListSectionUiState,
    isRefreshing: Boolean,
    titleFontScale: Float,
    onRefresh: () -> Unit,
    onFeedClick: (Long) -> Unit,
    onFeedLongClick: (Long) -> Unit,
    onReadAllClick: (() -> Unit)?,
) {
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            if (section.section == FeedListSection.PODCASTS) {
                                R.string.feed_list_section_unplayed
                            } else {
                                R.string.feed_list_section_unread
                            },
                            section.totalUnread,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (onReadAllClick != null && section.feeds.isNotEmpty()) {
                        TextButton(onClick = onReadAllClick) {
                            Text(stringResource(R.string.feed_list_read_all))
                        }
                    }
                }
            }
            items(section.feeds, key = { it.feed.id }) { item ->
                ListItemRow(
                    title = item.feed.userTitle ?: item.feed.title.orEmpty(),
                    subtitle = item.feed.description,
                    imageUrl = item.feed.imageUrl,
                    unreadCount = item.unreadCount,
                    titleFontScale = titleFontScale,
                    onClick = { onFeedClick(item.feed.id) },
                    onLongClick = { onFeedLongClick(item.feed.id) },
                )
            }
        }
    }
}
