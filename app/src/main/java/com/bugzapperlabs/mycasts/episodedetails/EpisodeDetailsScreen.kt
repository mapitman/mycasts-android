package com.bugzapperlabs.mycasts.episodedetails

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.episodelist.EpisodeDateFormatter
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.settings.scaleFactor
import com.bugzapperlabs.mycasts.playback.PlaybackUiState
import com.bugzapperlabs.mycasts.ui.components.ReaderText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailsScreen(
    modifier: Modifier = Modifier,
    viewModel: EpisodeDetailsViewModel = hiltViewModel(),
    onQueueClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val episodeDetailsFontSize by viewModel.episodeDetailsFontSize.collectAsState()
    val queueFeedback by viewModel.queueFeedback.collectAsState()
    val queuedItemIds by viewModel.queuedItemIds.collectAsState()
    val pendingDownloadItemIds by viewModel.pendingDownloadItemIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(queueFeedback) {
        queueFeedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeQueueFeedback()
        }
    }

    if (uiState.items.isEmpty() || uiState.initialItemNotFound) {
        Scaffold(modifier = modifier) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reader_no_content_to_show))
            }
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = uiState.initialIndex) { uiState.items.size }
    var zoomedImageUrl by remember { mutableStateOf<String?>(null) }

    val currentItem = uiState.items.getOrNull(pagerState.currentPage)

    // No back arrow (issue #128): system back gestures/buttons cover navigating away.
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    uiState.feedTitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                actions = {
                    if (currentItem?.isPodcastEpisode == true) {
                        // The currently-playing episode has no literal queue-table row (issue
                        // #171 removes it once playback starts, since it's shown pinned to the
                        // top of Next Up via the mini player instead) -- without this it's the
                        // one episode that would misleadingly read as "not queued" (issue #281).
                        val isQueued = currentItem.id in queuedItemIds || currentItem.id == playbackState.currentItemId
                        IconButton(onClick = {
                            if (isQueued) viewModel.removeFromQueue(currentItem.id) else viewModel.addToQueue(currentItem.id)
                        }) {
                            Icon(
                                if (isQueued) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(
                                    if (isQueued) R.string.cd_remove_from_next_up else R.string.cd_add_to_queue,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onQueueClick) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.cd_open_queue))
                    }
                    IconButton(onClick = {
                        val url = currentItem?.url ?: return@IconButton
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.cd_open_in_browser))
                    }
                    IconButton(onClick = {
                        val item = currentItem ?: return@IconButton
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, item.title)
                            putExtra(Intent.EXTRA_TEXT, item.url)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.cd_share))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                EpisodePage(
                    item = uiState.items[page],
                    onImageClick = { zoomedImageUrl = it },
                    fontScale = episodeDetailsFontSize.scaleFactor,
                    playbackState = playbackState,
                    feedImageUrl = uiState.feedImageUrl,
                    onTogglePlayPause = { viewModel.togglePlayPause(uiState.items[page]) },
                    isPendingDownload = uiState.items[page].id in pendingDownloadItemIds,
                    onDownload = { viewModel.downloadEnclosure(uiState.items[page]) },
                    onDelete = { viewModel.deleteDownload(uiState.items[page]) },
                )
            }
            // Moved off the top bar (issue #68): with a long feed title and up to four action
            // icons, "X of N" had no room left and was routinely clipped. A small pill floating
            // over the pager, gallery-style, doesn't compete with either for space.
            Text(
                text = stringResource(R.string.reader_page_position, pagerState.currentPage + 1, uiState.items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }

    zoomedImageUrl?.let { url ->
        ZoomableImageDialog(imageUrl = url, onDismiss = { zoomedImageUrl = null })
    }
}

@Composable
private fun EpisodePage(
    item: FeedItem,
    onImageClick: (String) -> Unit,
    fontScale: Float,
    playbackState: PlaybackUiState,
    feedImageUrl: String?,
    onTogglePlayPause: () -> Unit,
    isPendingDownload: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val coverImageUrl = (item.imageUrl ?: feedImageUrl).takeIf { item.isPodcastEpisode }
    val scrollState = rememberScrollState()
    val heroHeight = 220.dp
    val heroHeightPx = with(LocalDensity.current) { heroHeight.toPx() }
    // 0f while the hero image at the top is still fully in view; ramps up to 1f as it scrolls
    // out, so the same image fades in as a blurred backdrop instead of just disappearing.
    val scrolledPastHero = if (heroHeightPx > 0f) (scrollState.value / heroHeightPx).coerceIn(0f, 1f) else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        if (coverImageUrl != null && scrolledPastHero > 0f) {
            AsyncImage(
                model = coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).alpha(scrolledPastHero * 0.85f),
            )
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = scrolledPastHero * 0.25f)),
            )
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            if (coverImageUrl != null) {
                AsyncImage(
                    model = coverImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(heroHeight),
                )
            }
            Text(
                text = item.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                text = EpisodeDateFormatter.format(item.publishDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (item.isPodcastEpisode) {
                EpisodePlayRow(
                    isPlaying = playbackState.currentItemId == item.id && playbackState.isPlaying,
                    isBuffering = playbackState.currentItemId == item.id && playbackState.isBuffering,
                    isPlayed = item.isRead,
                    downloadedFilePath = item.downloadedFilePath,
                    downloadedBytes = item.downloadedBytes,
                    enclosureLength = item.enclosureLength,
                    isPendingDownload = isPendingDownload,
                    onTogglePlayPause = onTogglePlayPause,
                    onDownload = onDownload,
                    onDelete = onDelete,
                )
            }
            val imageUrl = item.imageUrl
            // The page background above already shows this as cover art -- skip the generic
            // image block for episodes so it isn't rendered twice on the page.
            if (imageUrl != null && !item.isPodcastEpisode) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { onImageClick(imageUrl) },
                )
            }
            val linkColor = MaterialTheme.colorScheme.primary
            val showNotes = remember(item.description, linkColor) {
                AnnotatedString.fromHtml(
                    item.description.orEmpty(),
                    linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            }
            ReaderText(
                text = showNotes,
                fontScale = fontScale,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

/**
 * Replaces the episode details page's old full in-page transport UI (issue #96) -- just a Play
 * button plus the download/delete action. Tapping Play queues this episode at the top of Next Up
 * and starts it immediately ([EpisodeDetailsViewModel.togglePlayPause] already does exactly that
 * via [com.bugzapperlabs.mycasts.playback.PlaybackController.play] when it isn't already the
 * current episode); once playing, the same button toggles pause/resume, and the full transport
 * controls (seek, chapters, speed, volume boost) live in the dedicated Now Playing screen instead.
 */
@Composable
private fun EpisodePlayRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    isPlayed: Boolean,
    downloadedFilePath: String?,
    downloadedBytes: Long?,
    enclosureLength: Long?,
    isPendingDownload: Boolean,
    onTogglePlayPause: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDownloaded = downloadedFilePath != null
    // isPendingDownload (issue #84) bridges the gap between tapping download and real progress
    // existing to react to -- downloadedBytes is only persisted once the worker has actually
    // written a chunk, which can lag noticeably behind the tap (WorkManager scheduling, network
    // constraints), during which the button used to show no feedback at all.
    val isDownloading = !isDownloaded && (downloadedBytes != null || isPendingDownload)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The episode list already shows played episodes greyed out (issue #89) -- this is the
        // explicit "you've listened to this" signal, shown where it's actually being read (#107).
        if (isPlayed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = stringResource(R.string.played_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTogglePlayPause, modifier = Modifier.weight(1f)) {
                if (isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play))
            }
            when {
                isDownloading -> {
                    // downloadedBytes can still be null here while isPendingDownload alone is
                    // true (issue #84) -- an indeterminate spinner (progress == null) is correct
                    // for that case, since there's no real progress yet to show.
                    val progress = if (downloadedBytes != null && enclosureLength != null && enclosureLength > 0) {
                        (downloadedBytes.toFloat() / enclosureLength).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    Box(modifier = Modifier.padding(12.dp)) {
                        // Tertiary (issue #95) as the podcast/enclosure accent, matching the
                        // download-status icon in the episode list.
                        if (progress != null) {
                            CircularProgressIndicator(
                                progress = { progress },
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                isDownloaded -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete_download))
                    }
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.cd_download_episode),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}
