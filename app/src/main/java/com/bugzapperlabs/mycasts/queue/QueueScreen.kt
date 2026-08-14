package com.bugzapperlabs.mycasts.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.playback.MiniPlayerBar
import com.bugzapperlabs.mycasts.playback.MiniPlayerViewModel
import com.bugzapperlabs.mycasts.playback.NowPlayingMiniStrip
import com.bugzapperlabs.mycasts.ui.components.CompactTopBar
import kotlinx.coroutines.launch

/** Height of the player sheet's peeked state -- sized to [NowPlayingMiniStrip], the sheet's own
 *  first row, not the full expanded controls below it. */
private val PLAYER_PEEK_HEIGHT = 72.dp

/** [androidx.compose.material3.BottomSheetDefaults.DragHandle] hardcodes 22dp of vertical padding
 *  around its pill -- much taller than the pill itself and not exposed as a parameter -- so this
 *  reproduces its look with a slim 6dp padding instead. */
@Composable
private fun SlimDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 6.dp)
            .size(width = 28.dp, height = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/**
 * Next Up as a first-class destination (issue #96): the queue list is the default/resting view,
 * with the player as a sheet pinned at the bottom (issue #96 -- built off a Pocket Casts "Up Next"
 * screen, not a copy of it). Peeked, the sheet shows only [NowPlayingMiniStrip]; dragging it up
 * reveals [MiniPlayerBar]'s full transport controls sliding over the queue list, dragging back down
 * returns to the plain list. Nothing to drag when nothing's playing -- the sheet has zero peek
 * height and no drag handle then, same as the old (pre-#96) player sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onOpenEpisode: (QueuedEpisode) -> Unit,
    onOpenCurrentEpisode: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
    miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val currentItemId by viewModel.currentItemId.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val removedEpisode by viewModel.removedEpisode.collectAsState()
    val playbackState by miniPlayerViewModel.playbackState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()

    // Undo snackbar for swipe-left removal from Next Up (issue #284).
    LaunchedEffect(removedEpisode) {
        val removed = removedEpisode ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(
                R.string.queue_feedback_removed,
                removed.episode.item.title.orEmpty(),
            ),
            actionLabel = context.getString(R.string.action_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoRemove(removed)
        }
        viewModel.consumeRemovedEpisode()
    }

    val hasCurrentItem = playbackState.currentItemId != null

    // Expands the player sheet once landing here from a mini-strip tap elsewhere in the app
    // (issue #96) -- MainActivity sets this before navigating, since arriving on the Next Up tab
    // still just peeked would defeat the point of tapping the strip to begin with.
    val pendingExpand by miniPlayerViewModel.pendingExpand.collectAsState()
    LaunchedEffect(pendingExpand) {
        if (pendingExpand) {
            scaffoldState.bottomSheetState.expand()
            miniPlayerViewModel.consumeExpandRequest()
        }
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        // inverseOnSurface, not a neutral surfaceContainer* step -- matches MiniPlayerBar/
        // NowPlayingMiniStrip's own Surface color (see their doc) so the drag-handle area between
        // them reads as the same distinct elevated surface, not a seam back to the queue's tone.
        sheetContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
        sheetShadowElevation = 8.dp,
        sheetPeekHeight = if (hasCurrentItem) PLAYER_PEEK_HEIGHT else 0.dp,
        sheetDragHandle = if (hasCurrentItem) { { SlimDragHandle() } } else null,
        sheetContent = {
            if (hasCurrentItem) {
                // MiniPlayerBar below already has its own transport row once the sheet is fully
                // open (issue #96) -- keeping this row's rewind/play/forward too would just be the
                // same three buttons twice on screen at once.
                val isExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded
                // fillMaxHeight here (issue #130) so the sheet is measured at the scaffold's full
                // height rather than just wrapping the strip + player's own content -- otherwise
                // BottomSheetScaffold's Expanded anchor (layoutHeight - sheetHeight) lands short of
                // the top instead of reaching it. MiniPlayerBar below takes the weight(1f) leftover
                // once the strip's fixed height is subtracted, so it's the one that stretches.
                //
                // The Column's height is shrunk by the status-bar inset (rather than left at the
                // scaffold's full height with a spacer inset from inside it) so the Expanded anchor
                // itself lands just below the status bar -- NowPlayingMiniStrip has to stay the
                // Column's first child with nothing above it, since the sheet's peeked state shows
                // only this same Column's own top sheetPeekHeight, i.e. the strip itself; a spacer
                // there would push the strip out of that peeked window instead of just out of the
                // status bar's way once expanded.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    Column(modifier = Modifier.height(maxHeight - statusBarInset)) {
                        NowPlayingMiniStrip(
                            playbackState = playbackState,
                            onClick = { coroutineScope.launch { scaffoldState.bottomSheetState.expand() } },
                            onTogglePlayPause = miniPlayerViewModel::togglePlayPause,
                            onSkipBackward = miniPlayerViewModel::skipBackward,
                            onSkipForward = miniPlayerViewModel::skipForward,
                            // Not the screen's true bottom edge -- the bottom nav bar below reserves
                            // this padding itself.
                            applyNavigationBarsPadding = false,
                            showControls = !isExpanded,
                        )
                        MiniPlayerBar(
                            playbackState = playbackState,
                            onOpenEpisode = onOpenCurrentEpisode,
                            onSeek = miniPlayerViewModel::seekTo,
                            onTogglePlayPause = miniPlayerViewModel::togglePlayPause,
                            onSkipBackward = miniPlayerViewModel::skipBackward,
                            onSkipForward = miniPlayerViewModel::skipForward,
                            onNextChapter = miniPlayerViewModel::nextChapter,
                            onPreviousChapter = miniPlayerViewModel::previousChapter,
                            onSpeedChange = miniPlayerViewModel::setSpeed,
                            onVolumeBoostChange = miniPlayerViewModel::setVolumeBoost,
                            onStop = miniPlayerViewModel::stop,
                            applyNavigationBarsPadding = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        topBar = {
            // No title (issue #127): the bottom nav's highlighted "Next Up" tab already conveys
            // this is the queue screen. CompactTopBar replaces TopAppBar here too, so the sort
            // action's own ~48dp touch target governs this bar's height instead of Material's
            // taller ~64dp component height on top of it.
            CompactTopBar {
                Spacer(modifier = Modifier.weight(1f))
                if (queue.size > 1) {
                    IconButton(onClick = viewModel::sortByPublishDate) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(
                                if (sortAscending) {
                                    R.string.cd_sort_queue_oldest_first
                                } else {
                                    R.string.cd_sort_queue_newest_first
                                },
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { innerPadding ->
        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ReorderableQueueList(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                queue = queue,
                currentItemId = currentItemId,
                onReorder = { ids, onComplete -> viewModel.reorder(ids, onComplete) },
                onRemove = viewModel::remove,
                onOpen = onOpenEpisode,
                onPlay = viewModel::playNow,
            )
        }
    }
}
