package com.bugzapperlabs.myfeeds.reader

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.articlelist.ArticleDateFormatter
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.settings.scaleFactor
import com.bugzapperlabs.myfeeds.playback.PLAYER_ARTWORK_KEY
import com.bugzapperlabs.myfeeds.playback.PlaybackUiState
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ReaderScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onCurrentItemChange: (String?) -> Unit = {},
    onQueueClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val articleFontSize by viewModel.articleFontSize.collectAsState()
    val queueFeedback by viewModel.queueFeedback.collectAsState()
    val queuedItemIds by viewModel.queuedItemIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(queueFeedback) {
        queueFeedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeQueueFeedback()
        }
    }

    if (uiState.items.isEmpty()) {
        Scaffold(modifier = modifier) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reader_no_content_to_show))
            }
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = uiState.initialIndex) { uiState.items.size }
    var zoomedImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState, uiState.items) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                uiState.items.getOrNull(page)?.let { viewModel.markRead(it) }
            }
    }

    val currentItem = uiState.items.getOrNull(pagerState.currentPage)

    // Lets MainActivity hide the mini-player only while the on-screen page is the episode that's
    // actually playing (issue #97) -- HorizontalPager swipes don't renavigate, so the nav route's
    // itemId argument stays fixed at whichever episode was first opened and can't be used for this.
    LaunchedEffect(currentItem?.id) { onCurrentItemChange(currentItem?.id) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        uiState.feedTitle?.let { Text(it) }
                        Text(
                            text = stringResource(R.string.reader_page_position, pagerState.currentPage + 1, uiState.items.size),
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
        HorizontalPager(state = pagerState, modifier = Modifier.padding(innerPadding).fillMaxSize()) { page ->
            ArticlePage(
                item = uiState.items[page],
                onImageClick = { zoomedImageUrl = it },
                fontScale = articleFontSize.scaleFactor,
                playbackState = playbackState,
                feedImageUrl = uiState.feedImageUrl,
                onTogglePlayPause = { viewModel.togglePlayPause(uiState.items[page]) },
                onSeek = viewModel::seekTo,
                onDownload = { viewModel.downloadEnclosure(uiState.items[page]) },
                onDelete = { viewModel.deleteDownload(uiState.items[page]) },
                onSpeedChange = viewModel::setPlaybackSpeed,
                onVolumeBoostChange = viewModel::setVolumeBoost,
                onSkipBackward = viewModel::skipBackward,
                onSkipForward = viewModel::skipForward,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }

    zoomedImageUrl?.let { url ->
        ZoomableImageDialog(imageUrl = url, onDismiss = { zoomedImageUrl = null })
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ArticlePage(
    item: FeedItem,
    onImageClick: (String) -> Unit,
    fontScale: Float,
    playbackState: PlaybackUiState,
    feedImageUrl: String?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeBoostChange: (Int) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
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
                // Only the page that's actually playing takes part in the artwork shared element
                // (issue #112) -- other pages in the pager show the same episode/feed artwork
                // without it, since a key can only belong to one on-screen element at a time.
                val heroModifier = if (item.id == playbackState.currentItemId) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = PLAYER_ARTWORK_KEY),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                } else {
                    Modifier
                }
                AsyncImage(
                    model = coverImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(heroHeight).then(heroModifier),
                )
            }
            Text(
                text = item.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                text = ArticleDateFormatter.format(item.publishDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (item.isPodcastEpisode) {
                PodcastPlayerControls(
                    isCurrentItem = playbackState.currentItemId == item.id,
                    isPlayed = item.isRead,
                    playbackState = playbackState,
                    savedPositionMs = item.enclosurePosition?.let { (it * 1000).toLong() },
                    savedDurationMs = item.enclosureDurationMs,
                    downloadedFilePath = item.downloadedFilePath,
                    downloadedBytes = item.downloadedBytes,
                    enclosureLength = item.enclosureLength,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeek = onSeek,
                    onDownload = onDownload,
                    onDelete = onDelete,
                    onSpeedChange = onSpeedChange,
                    onVolumeBoostChange = onVolumeBoostChange,
                    onSkipBackward = onSkipBackward,
                    onSkipForward = onSkipForward,
                    onNextChapter = onNextChapter,
                    onPreviousChapter = onPreviousChapter,
                )
            }
            val imageUrl = item.imageUrl
            // The page background above already shows this as cover art -- skip the generic
            // article-image block for episodes so it isn't rendered twice on the page.
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
            ArticleBody(
                html = item.description.orEmpty(),
                baseUrl = item.url,
                fontScale = fontScale,
                translucentBackground = coverImageUrl != null,
            )
        }
    }
}

@Composable
private fun PodcastPlayerControls(
    isCurrentItem: Boolean,
    isPlayed: Boolean,
    playbackState: PlaybackUiState,
    savedPositionMs: Long?,
    savedDurationMs: Long?,
    downloadedFilePath: String?,
    downloadedBytes: Long?,
    enclosureLength: Long?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeBoostChange: (Int) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
) {
    // While actively loaded *and* the player has a real duration, show the live player position.
    // Otherwise -- not yet played this session, still buffering, or the mini-player was dismissed
    // (issue #75) -- fall back to the saved resume position/duration (itunes:duration, where the
    // feed provides it) so progress doesn't visually reset to 0:00. If duration truly isn't known
    // (feed has no itunes:duration and playback hasn't buffered in), stay at 0 rather than
    // overflowing the slider's fallback range with a positionMs the duration can't yet bound.
    val hasLiveDuration = isCurrentItem && playbackState.durationMs > 0
    val durationMs = when {
        hasLiveDuration -> playbackState.durationMs
        savedDurationMs != null && savedDurationMs > 0 -> savedDurationMs
        else -> 0L
    }
    val positionMs = when {
        hasLiveDuration -> playbackState.positionMs
        durationMs > 0 -> (savedPositionMs ?: 0L).coerceIn(0L, durationMs)
        else -> 0L
    }
    val isPlaying = isCurrentItem && playbackState.isPlaying
    val isDownloaded = downloadedFilePath != null
    val isDownloading = !isDownloaded && downloadedBytes != null
    // issue #95: chapter nav only makes sense while this episode is the one actually loaded, since
    // playbackState.chapters/currentChapter reflect whatever's currently playing, not this item.
    val hasChapters = isCurrentItem && playbackState.chapters.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The article list already shows played episodes greyed out (issue #89) -- this is the
        // explicit "you've listened to this" signal, shown where it's actually being read (#107).
        if (isPlayed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                )
                Text(
                    text = stringResource(R.string.played_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasChapters) {
            val chapterIndex = playbackState.currentChapterIndex
            val chapterTitle = playbackState.currentChapter?.title
            Text(
                text = stringResource(R.string.reader_chapter_label, chapterIndex + 1, playbackState.chapters.size)
                    .let { if (chapterTitle != null) "$it: $chapterTitle" else it },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = isCurrentItem,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration((durationMs - positionMs).coerceAtLeast(0L)), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No stock Material icon for an exact 15s glyph (only 5/10/30) -- the plain circular
            // Replay arrow (mirrored for forward) reads as "skip" without implying a wrong duration.
            IconButton(onClick = onSkipBackward, enabled = isCurrentItem, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                Icon(
                    Icons.Filled.Replay,
                    contentDescription = stringResource(R.string.cd_rewind),
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                )
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
                if (isCurrentItem && playbackState.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(TRANSPORT_ICON_SIZE), strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                        modifier = Modifier.size(PLAY_ICON_SIZE),
                    )
                }
            }
            IconButton(onClick = onSkipForward, enabled = isCurrentItem, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                Icon(
                    Icons.Filled.Replay,
                    contentDescription = stringResource(R.string.cd_forward),
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE).graphicsLayer(scaleX = -1f),
                )
            }
            when {
                isDownloading -> {
                    val progress = if (enclosureLength != null && enclosureLength > 0) {
                        (downloadedBytes!!.toFloat() / enclosureLength).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    Box(modifier = Modifier.padding(8.dp)) {
                        if (progress != null) {
                            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
                        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.cd_download_episode))
                    }
                }
            }
        }
        // Chapter nav flanks the speed selector on its own row (issue #185/#186) -- keeps the main
        // transport row to just 3-4 buttons so it never overflows screen width.
        if (isCurrentItem) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChapters) {
                    IconButton(onClick = onPreviousChapter) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous_chapter))
                    }
                }
                TextButton(onClick = {
                    val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { it >= playbackState.speed }.coerceAtLeast(0)
                    onSpeedChange(PLAYBACK_SPEEDS[(currentIndex + 1) % PLAYBACK_SPEEDS.size])
                }) {
                    Text(formatSpeed(playbackState.speed))
                }
                // issue #202: cycles the same discrete levels as Feed Properties, so the value
                // stays consistent whichever surface changed it last.
                TextButton(onClick = {
                    val currentIndex = VOLUME_BOOST_LEVELS.indexOf(playbackState.volumeBoostMillibels).let {
                        if (it < 0) 0 else it
                    }
                    onVolumeBoostChange(VOLUME_BOOST_LEVELS[(currentIndex + 1) % VOLUME_BOOST_LEVELS.size])
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.cd_volume_boost),
                        modifier = Modifier.size(18.dp),
                    )
                    if (playbackState.volumeBoostMillibels > 0) {
                        Text(
                            text = "+${playbackState.volumeBoostMillibels / 100}dB",
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (hasChapters) {
                    IconButton(onClick = onNextChapter) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next_chapter))
                    }
                }
            }
        }
    }
}

private val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** Millibel gain levels cycled by the player's volume boost button (issue #202) -- matches the
 *  Off/Low/Medium/High levels offered in Feed Properties. */
private val VOLUME_BOOST_LEVELS = listOf(0, 600, 1200, 1800)

/** issue #186: bigger than the default 48dp/24dp IconButton so transport controls stay easy to
 *  hit at a glance (e.g. while driving), with play/pause sized up further as the primary action. */
private val TRANSPORT_BUTTON_SIZE = 64.dp
private val TRANSPORT_ICON_SIZE = 40.dp
private val PLAY_BUTTON_SIZE = 88.dp
private val PLAY_ICON_SIZE = 56.dp

private fun formatSpeed(speed: Float): String =
    "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ArticleBody(html: String, baseUrl: String?, fontScale: Float, translucentBackground: Boolean = false) {
    val context = LocalContext.current
    val sanitized = remember(html) { HtmlSanitizer.sanitize(html) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surface
    val bodyFontSizePx = (16 * fontScale).toInt()

    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url == null) return false
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                }
            }
        },
        update = { webView ->
            val textColorHex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
            // Podcast pages have a cover-art backdrop behind the whole page (issue #97) -- let it
            // bleed through the article body's background too instead of the WebView masking it
            // with a fully opaque surface color once the page scrolls into the text.
            val bgColor = backgroundColor.toArgb()
            val backgroundCss = if (translucentBackground) {
                "rgba(${(bgColor shr 16) and 0xFF}, ${(bgColor shr 8) and 0xFF}, ${bgColor and 0xFF}, 0.75)"
            } else {
                String.format("#%06X", 0xFFFFFF and bgColor)
            }
            val styledHtml = """
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                  body { color: $textColorHex; background-color: $backgroundCss; font-family: sans-serif; font-size: ${bodyFontSizePx}px; }
                  img { max-width: 100%; height: auto; }
                  a { color: $textColorHex; }
                </style></head><body>$sanitized</body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(baseUrl, styledHtml, "text/html", "UTF-8", null)
        },
    )
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
