package com.bugzapperlabs.myfeeds.playback

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bugzapperlabs.myfeeds.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Shared-element keys (issue #112) matching across [MiniPlayerBar] and the reader's hero image --
 * only one of those two is ever on-screen carrying a given key at once (except mid-transition,
 * which is exactly when Compose is meant to animate between them). */
const val PLAYER_CONTAINER_KEY = "player-container"
const val PLAYER_ARTWORK_KEY = "player-artwork"

/** issue #186: bigger than the default 48dp/24dp IconButton so transport controls stay easy to
 *  hit at a glance (e.g. while driving), with play/pause sized up further as the primary action. */
private val TRANSPORT_BUTTON_SIZE = 64.dp
private val TRANSPORT_ICON_SIZE = 40.dp
private val PLAY_BUTTON_SIZE = 88.dp
private val PLAY_ICON_SIZE = 56.dp

/** issue #279: minimum upward drag on [NowPlayingMiniStrip] before it's treated as a deliberate
 *  swipe-up-to-expand rather than an incidental touch/scroll. */
private val MINI_STRIP_SWIPE_UP_THRESHOLD = 24.dp

/** issue #279: caps how far the strip visually follows the finger before the drag commits, so a
 *  long drag doesn't send it sliding off past where it's actually heading. */
private val MINI_STRIP_SWIPE_UP_MAX_DRAG = 120.dp

/**
 * Persistent "now playing" bar (issue #66) shown across the app whenever [PlaybackController] has
 * an episode loaded, so playback stays visible/controllable after navigating away from the reader.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    onClick: () -> Unit,
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
    // True for every standalone/pinned-at-the-bottom use of this bar, where the nav bar sits right
    // below it and needs the padding reserved. Only the player sheet's *expanded* content (issue
    // #197) -- where this is a sticky header with the Next Up list following below it, not the
    // screen's actual bottom edge -- passes false, since that reserved space just becomes unwanted
    // blank space between the speed-button row and the list beneath it.
    applyNavigationBarsPadding: Boolean = true,
) {
    val hasChapters = playbackState.chapters.isNotEmpty()
    with(sharedTransitionScope) {
        Surface(
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
                .sharedBounds(
                    rememberSharedContentState(key = PLAYER_CONTAINER_KEY),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
        ) {
        Box {
            // Blurred cover art as a backdrop. The scrim fades from mostly-transparent at the top
            // to the bar's own solid surface color at the bottom -- rather than a flat dim -- so
            // when this is the player sheet's header (issue #195), the art visually merges into
            // the plain-colored Next Up list starting right where this bar ends, instead of
            // cutting off abruptly.
            if (playbackState.artworkUrl != null) {
                AsyncImage(
                    model = playbackState.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(24.dp).alpha(0.6f),
                )
                Box(
                    modifier = Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                            1f to MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                )
            }
        Column(modifier = if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier) {
            val progress = if (playbackState.durationMs > 0) {
                (playbackState.positionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(playbackState.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(
                    formatDuration((playbackState.durationMs - playbackState.positionMs).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (playbackState.artworkUrl != null) {
                    AsyncImage(
                        model = playbackState.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
                            .sharedElement(
                                rememberSharedContentState(key = PLAYER_ARTWORK_KEY),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = playbackState.title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playbackState.feedTitle != null) {
                        Text(
                            text = playbackState.feedTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hasChapters) {
                        Text(
                            text = chapterLabel(playbackState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Same control layout as ExpandedPlayerBar/the reader's inline player (issue #194):
            // main row is always just rewind/play/forward/stop, chapter nav flanks the speed
            // selector on its own row below.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSkipBackward, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = stringResource(R.string.cd_rewind),
                        modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                    )
                }
                IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(TRANSPORT_ICON_SIZE), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play),
                            modifier = Modifier.size(PLAY_ICON_SIZE),
                        )
                    }
                }
                IconButton(onClick = onSkipForward, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = stringResource(R.string.cd_forward),
                        modifier = Modifier.size(TRANSPORT_ICON_SIZE).graphicsLayer(scaleX = -1f),
                    )
                }
                IconButton(onClick = onStop, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_stop_playback),
                        modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
    }
}

/**
 * An even more minimal now-playing indicator (issue #197) -- swiping [MiniPlayerBar] down further
 * (past its own resting/peek position, when it's the player sheet's collapsed header) shrinks to
 * this instead of dismissing playback entirely: just enough to see what's playing and toggle it.
 *
 * Shares [PLAYER_CONTAINER_KEY]/[PLAYER_ARTWORK_KEY] with [MiniPlayerBar] (issue #279), the same
 * mechanism issue #112 uses for the mini-bar<->reader transition, so Compose continuously resizes
 * the container and artwork between the two instead of an instant swap or a plain cross-fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingMiniStrip(
    playbackState: PlaybackUiState,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    // issue #279: swiping the strip back up restores the medium player, mirroring tapping it.
    onSwipeUp: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    // issue #279: liveDragPx tracks the finger 1:1 while the drag is active (a plain float, no
    // animation, so it never lags behind touch); settleAnim only takes over once the finger lifts,
    // either springing back to 0 (drag didn't clear the threshold) or quickly relaxing to 0 while
    // onSwipeUp's partialExpand() hands off to the sharedBounds transition for the rest of the trip.
    var liveDragPx by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    with(sharedTransitionScope) {
        Surface(
            // issue #279: clickable's own press/tap recognizer was consuming the touch before the
            // drag detector below got a chance to see the movement, so a swipe up registered as an
            // instant tap instead of a tracked drag. Detecting taps in our own pointerInput instead
            // -- as a sibling to (not layered with) the drag detector -- lets both observe the same
            // raw gesture and resolve it correctly as one or the other.
            modifier = modifier.fillMaxWidth()
                .sharedBounds(
                    rememberSharedContentState(key = PLAYER_CONTAINER_KEY),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                .offset { IntOffset(0, (liveDragPx + settleAnim.value).roundToInt()) }
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                }
                .pointerInput(onSwipeUp) {
                    val maxDrag = MINI_STRIP_SWIPE_UP_MAX_DRAG.toPx()
                    val commitThreshold = MINI_STRIP_SWIPE_UP_THRESHOLD.toPx()
                    // issue #279: commits as soon as the drag crosses the threshold -- not on
                    // release -- so onSwipeUp's partialExpand() (and the sharedBounds growth it
                    // triggers) starts while the finger is still moving, the same way the sheet's
                    // own down-drag flips its target mid-gesture rather than waiting for lift-off.
                    var committed = false
                    detectVerticalDragGestures(
                        onDragStart = {
                            committed = false
                            coroutineScope.launch { settleAnim.snapTo(0f) }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (!committed) {
                                val newValue = (liveDragPx + dragAmount).coerceIn(-maxDrag, 0f)
                                if (newValue < -commitThreshold) {
                                    // issue #279: snap our manual offset away immediately (no
                                    // animateTo back to 0) rather than run it concurrently with
                                    // the sharedBounds resize that onSwipeUp triggers -- two
                                    // simultaneous animations moving the same surface at once was
                                    // the actual cause of the stutter, not the resize itself.
                                    committed = true
                                    liveDragPx = 0f
                                    coroutineScope.launch { settleAnim.snapTo(0f) }
                                    onSwipeUp()
                                } else {
                                    liveDragPx = newValue
                                }
                            }
                        },
                        onDragEnd = {
                            if (!committed) {
                                val start = liveDragPx
                                liveDragPx = 0f
                                coroutineScope.launch {
                                    settleAnim.snapTo(start)
                                    settleAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                }
                            }
                        },
                        onDragCancel = {
                            if (!committed) {
                                val start = liveDragPx
                                liveDragPx = 0f
                                coroutineScope.launch {
                                    settleAnim.snapTo(start)
                                    settleAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                }
                            }
                        },
                    )
                },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
        ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (playbackState.artworkUrl != null) {
                AsyncImage(
                    model = playbackState.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                        .sharedElement(
                            rememberSharedContentState(key = PLAYER_ARTWORK_KEY),
                            animatedVisibilityScope = animatedVisibilityScope,
                        ),
                )
            }
            Text(
                text = playbackState.title.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            IconButton(onClick = onTogglePlayPause) {
                if (playbackState.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playbackState.isPlaying) R.string.cd_pause else R.string.cd_play),
                    )
                }
            }
        }
        }
    }
}

/** "Chapter N of M[: Title]" (issue #95). */
@Composable
private fun chapterLabel(playbackState: PlaybackUiState): String {
    val label = stringResource(
        R.string.reader_chapter_label,
        playbackState.currentChapterIndex + 1,
        playbackState.chapters.size,
    )
    val title = playbackState.currentChapter?.title
    return if (title != null) "$label: $title" else label
}

private val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** Millibel gain levels cycled by the player's volume boost button (issue #202) -- matches the
 *  Off/Low/Medium/High levels offered in Feed Properties. */
private val VOLUME_BOOST_LEVELS = listOf(0, 600, 1200, 1800)

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
