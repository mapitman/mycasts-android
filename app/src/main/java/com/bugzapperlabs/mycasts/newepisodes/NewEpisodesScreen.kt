package com.bugzapperlabs.mycasts.newepisodes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.episodelist.EpisodeDateFormatter
import com.bugzapperlabs.mycasts.ui.components.ListItemRow

/** The episodes found since the app was last opened (issue #161) -- reached by tapping the new
 *  episodes notification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewEpisodesScreen(
    modifier: Modifier = Modifier,
    viewModel: NewEpisodesViewModel = hiltViewModel(),
    onEpisodeClick: (feedId: Long, itemId: String) -> Unit = { _, _ -> },
) {
    val episodes by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier,
        // No back arrow (issue #128): system back gestures/buttons cover navigating away, and
        // this route is in BOTTOM_NAV_ROUTES so the nav bar also offers a way off it.
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.new_episodes_title)) })
        },
    ) { innerPadding ->
        if (episodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.new_episodes_empty))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                items(episodes, key = { it.item.id }) { episode ->
                    ListItemRow(
                        title = episode.item.title.orEmpty(),
                        subtitle = stringResource(
                            R.string.new_episodes_row_subtitle,
                            episode.feedTitle.orEmpty(),
                            EpisodeDateFormatter.format(episode.item.publishDate),
                        ),
                        imageUrl = episode.item.imageUrl ?: episode.feedImageUrl,
                        onClick = { onEpisodeClick(episode.item.feedId, episode.item.id) },
                    )
                }
            }
        }
    }
}
