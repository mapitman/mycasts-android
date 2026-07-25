package com.bugzapperlabs.myfeeds.playback

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.local.QueuedEpisode
import com.bugzapperlabs.myfeeds.queue.ReorderableQueueList

/**
 * Content of the persistent player bottom sheet (issue #195): [MiniPlayerBar] as a sticky header
 * -- it's all that's visible while the sheet sits at its collapsed peek height -- with the Next
 * Up queue revealed below it as the sheet is dragged open. The whole column has to be naturally
 * taller than the sheet's peek height (not conditionally sized to it) for
 * [androidx.compose.material3.BottomSheetScaffold] to compute a distinct "expanded" anchor at
 * all -- but it also has to actually shrink to fit a short queue (issue #197) rather than always
 * claiming the full screen and leaving blank space below a handful of items, so the column
 * itself wraps its content and only [ReorderableQueueList] gets a *capped* (not forced) height
 * via `heightIn(max =)`, which a plain (non-fillMaxSize) [androidx.compose.foundation.lazy.LazyColumn]
 * already respects by sizing down to its actual item count within that ceiling.
 *
 * That cap is the screen height minus the status bar inset, not the full screen -- edge-to-edge
 * means [androidx.compose.material3.BottomSheetScaffold] otherwise has no notion of the status bar
 * at all, so without this the sheet (and its drag handle) could expand straight into it.
 *
 * The status bar inset alone isn't quite enough, though: [maxHeight] caps this content, but
 * [androidx.compose.material3.BottomSheetScaffold]'s `sheetDragHandle` slot (`SlimDragHandle` in
 * `MainActivity`) is rendered *above* this content in the same sheet surface, so the sheet's total
 * expanded height is the handle's height plus this. [DRAG_HANDLE_ALLOWANCE] accounts for that
 * (SlimDragHandle is a 3dp pill with 14dp of padding above and below, so 31dp) plus a little extra
 * safety margin, so the handle itself doesn't poke into the status bar either.
 */
private val DRAG_HANDLE_ALLOWANCE = 40.dp
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerBottomSheetContent(
    playbackState: PlaybackUiState,
    queue: List<QueuedEpisode>,
    onOpenCurrentEpisode: () -> Unit,
    onQueueEpisodeClick: (QueuedEpisode) -> Unit,
    onReorder: (List<String>, onComplete: () -> Unit) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeBoostChange: (Int) -> Unit,
    onStop: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - statusBarHeight - DRAG_HANDLE_ALLOWANCE
    Column(modifier = modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        if (playbackState.currentItemId != null) {
            MiniPlayerBar(
                playbackState = playbackState,
                onClick = onOpenCurrentEpisode,
                onTogglePlayPause = onTogglePlayPause,
                onSkipBackward = onSkipBackward,
                onSkipForward = onSkipForward,
                onNextChapter = onNextChapter,
                onPreviousChapter = onPreviousChapter,
                onSpeedChange = onSpeedChange,
                onVolumeBoostChange = onVolumeBoostChange,
                onStop = onStop,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                // false (issue #197): here MiniPlayerBar is a sticky header with the Next Up list
                // following below it, not pinned at the screen's actual bottom edge, so reserving
                // nav-bar space here just leaves unwanted blank space above that list.
                applyNavigationBarsPadding = false,
            )
        }
        Text(
            text = stringResource(R.string.queue_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.queue_empty))
            }
        } else {
            ReorderableQueueList(
                modifier = Modifier.heightIn(max = maxHeight),
                queue = queue,
                onReorder = onReorder,
                onRemove = onRemoveFromQueue,
                onClick = onQueueEpisodeClick,
            )
        }
    }
}
