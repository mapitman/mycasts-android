package com.bugzapperlabs.mycasts.wear.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

// One rotary "tick" is a handful of scroll pixels (device-dependent); scaling by this amount
// gives a seek granularity that feels proportional to how far the crown/bezel is turned, rather
// than jumping by a huge or imperceptibly small amount per tick.
private const val ROTARY_MS_PER_PIXEL = 40L

/** The watch's transport screen (issue #276/#285): play/pause, skip forward/backward, a
 *  draggable/rotary-seekable progress bar, next/previous-episode, and a speed toggle -- trimmed
 *  from `:app`'s in-page player only in that there's no chapters UI, since chapters aren't part of
 *  the synced [com.bugzapperlabs.mycasts.data.local.Feed]/[com.bugzapperlabs.mycasts.data.local.FeedItem]
 *  snapshot on the watch. */
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .onRotaryScrollEvent { event ->
                val deltaMs = (event.verticalScrollPixels * ROTARY_MS_PER_PIXEL).roundToLong()
                viewModel.seekTo((uiState.positionMs + deltaMs).coerceIn(0L, uiState.durationMs))
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(uiState.title ?: "Nothing playing", maxLines = 2)
        uiState.feedTitle?.let { Text(it, maxLines = 1) }

        SeekBar(
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            onSeek = viewModel::seekTo,
        )
        Text("${formatDuration(uiState.positionMs)} / ${formatDuration(uiState.durationMs)}")
        if (uiState.isBuffering) Text("Buffering…")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = viewModel::skipBackward,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.FastRewind, contentDescription = "Back 15 seconds")
            }
            Button(
                onClick = viewModel::togglePlayPause,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(ButtonDefaults.DefaultButtonSize),
            ) {
                Icon(
                    if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                )
            }
            Button(
                onClick = viewModel::skipForward,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.FastForward, contentDescription = "Forward 30 seconds")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = viewModel::previousEpisode,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Restart episode")
            }
            CompactChip(
                onClick = viewModel::cycleSpeed,
                label = { Text("${uiState.speed}x") },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Button(
                onClick = viewModel::nextEpisode,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next episode")
            }
        }
    }
}

/** A draggable progress indicator (issue #285) -- position updates live while dragging and the
 *  actual seek fires once on release, so a slow drag doesn't spam the player with intermediate
 *  seeks. Rotary input (the screen's own [Modifier.onRotaryScrollEvent]) is the other way to seek,
 *  for watches without (or in addition to) a touchscreen-friendly drag target this small. */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).roundToLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray, RoundedCornerShape(3.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Color.White, RoundedCornerShape(3.dp)),
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L))
    return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
