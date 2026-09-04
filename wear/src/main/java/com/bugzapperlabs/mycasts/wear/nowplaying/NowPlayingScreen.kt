package com.bugzapperlabs.mycasts.wear.nowplaying

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import kotlin.math.sign

// Accumulated scroll pixels needed before firing one AudioManager.ADJUST_RAISE/LOWER step.
// Calibrated against real Pixel Watch 4 hardware (issue #285 follow-up): a single unhurried
// crown turn accumulates roughly 150-250 scroll pixels total across its burst of events, so this
// is low enough that one turn produces several perceptible volume steps, not just one.
private const val ROTARY_PIXELS_PER_VOLUME_STEP = 15f

/** The watch's transport screen (issue #276/#285): play/pause, skip forward/backward, a
 *  draggable seek bar, next/previous-episode, and a speed toggle -- trimmed from `:app`'s in-page
 *  player only in that there's no chapters UI, since chapters aren't part of the synced
 *  [com.bugzapperlabs.mycasts.data.local.Feed]/[com.bugzapperlabs.mycasts.data.local.FeedItem]
 *  snapshot on the watch.
 *
 *  The rotary input (crown/bezel) drives system media volume here, not seeking -- that's the
 *  Wear OS convention (with its own rounded volume indicator), and it isn't automatic: a media
 *  app has to explicitly forward rotary events to [AudioManager.adjustStreamVolume] itself. */
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var accumulatedScrollPixels by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .onRotaryScrollEvent { event ->
                accumulatedScrollPixels += event.verticalScrollPixels
                if (accumulatedScrollPixels.absoluteValue >= ROTARY_PIXELS_PER_VOLUME_STEP) {
                    val direction = if (accumulatedScrollPixels.sign > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    accumulatedScrollPixels = 0f
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // basicMarquee (issue #285 follow-up): matches how Wear OS's own system media control
        // card scrolls a title too long to fit, rather than truncating it.
        Text(
            uiState.title ?: "Nothing playing",
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
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
 *  seeks. */
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
