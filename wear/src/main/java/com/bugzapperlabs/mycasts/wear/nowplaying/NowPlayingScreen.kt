package com.bugzapperlabs.mycasts.wear.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import java.util.concurrent.TimeUnit

/** The watch's transport screen (issue #276) -- play/pause and a position readout, trimmed from
 *  `:app`'s in-page player: no seek bar/chapters/speed controls, since [WearPlaybackUiState] (and
 *  the synced [com.bugzapperlabs.mycasts.data.local.Feed] it's built from) doesn't carry those. */
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(uiState.title ?: "Nothing playing", maxLines = 2)
        uiState.feedTitle?.let { Text(it, maxLines = 1) }
        Text("${formatDuration(uiState.positionMs)} / ${formatDuration(uiState.durationMs)}")
        Chip(
            onClick = viewModel::togglePlayPause,
            label = {
                Text(
                    when {
                        uiState.isBuffering -> "Buffering…"
                        uiState.isPlaying -> "Pause"
                        else -> "Play"
                    },
                )
            },
            colors = ChipDefaults.primaryChipColors(),
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L))
    return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
