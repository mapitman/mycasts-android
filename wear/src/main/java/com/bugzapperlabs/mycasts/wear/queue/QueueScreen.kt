package com.bugzapperlabs.mycasts.wear.queue

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode

/** The watch's Next Up list (issue #276) -- read-only (no reorder/remove; those stay phone-side
 *  edits that sync down), tapping an episode starts playing it. */
@Composable
fun QueueScreen(onEpisodeStarted: () -> Unit, viewModel: QueueViewModel = hiltViewModel()) {
    val queue by viewModel.queue.collectAsState()
    val currentItemId by viewModel.currentItemId.collectAsState()

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("Next Up") } }
        if (queue.isEmpty()) {
            item { Text("Nothing queued") }
        }
        items(queue, key = { it.item.id }) { episode ->
            QueueRow(
                episode = episode,
                isCurrentlyPlaying = episode.item.id == currentItemId,
                onClick = { viewModel.play(episode.item, onStarted = onEpisodeStarted) },
            )
        }
    }
}

@Composable
private fun QueueRow(episode: QueuedEpisode, isCurrentlyPlaying: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(episode.item.title.orEmpty(), maxLines = 1) },
        secondaryLabel = {
            Text(if (isCurrentlyPlaying) "Now Playing" else episode.feedTitle.orEmpty(), maxLines = 1)
        },
        colors = if (isCurrentlyPlaying) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
