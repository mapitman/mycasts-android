package com.bugzapperlabs.mycasts.podcastdetails

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.addfeed.AddFeedUiState
import com.bugzapperlabs.mycasts.episodelist.EpisodeDateFormatter
import com.bugzapperlabs.mycasts.data.feed.ParsedFeedItem
import org.jsoup.Jsoup

/** Episode/podcast descriptions may be HTML (e.g. `content:encoded`) -- these rows use plain
 *  [Text], not a WebView, so tags are stripped to text rather than sanitized-and-kept as in
 *  [com.bugzapperlabs.mycasts.reader.HtmlSanitizer]. */
private fun plainText(html: String): String = if (html.isBlank()) "" else Jsoup.parse(html).text()

private const val MAX_EPISODES_SHOWN = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailsScreen(
    modifier: Modifier = Modifier,
    viewModel: PodcastDetailsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val previewState by viewModel.previewState.collectAsState()
    val subscribeState by viewModel.subscribeState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(subscribeState) {
        if (subscribeState is AddFeedUiState.Success) onDone()
    }

    val title = (previewState as? PodcastPreviewState.Loaded)?.feed?.title
        ?: viewModel.initialTitle
        ?: stringResource(R.string.podcast_details_title)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = previewState) {
                is PodcastPreviewState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is PodcastPreviewState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is PodcastPreviewState.Loaded -> PodcastDetailsContent(
                    state = state,
                    subscribeState = subscribeState,
                    onSubscribe = viewModel::subscribe,
                    onVisitWebsite = {
                        if (state.feed.siteUrl.isNotBlank()) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.feed.siteUrl)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PodcastDetailsContent(
    state: PodcastPreviewState.Loaded,
    subscribeState: AddFeedUiState,
    onSubscribe: () -> Unit,
    onVisitWebsite: () -> Unit,
) {
    val feed = state.feed
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (feed.imageUrl != null) {
                AsyncImage(
                    model = feed.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.padding(start = if (feed.imageUrl != null) 12.dp else 0.dp)) {
                Text(feed.title, style = MaterialTheme.typography.titleLarge)
                if (feed.siteUrl.isNotBlank()) {
                    TextButton(onClick = onVisitWebsite, modifier = Modifier.padding(start = (-8).dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        )
                        Text(stringResource(R.string.podcast_details_visit_website))
                    }
                }
            }
        }
        if (feed.description.isNotBlank()) {
            Text(
                text = plainText(feed.description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = onSubscribe,
            enabled = subscribeState !is AddFeedUiState.Loading,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            if (subscribeState is AddFeedUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.podcast_details_subscribe_button))
            }
        }
        if (subscribeState is AddFeedUiState.Error) {
            Text(
                text = subscribeState.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        Text(stringResource(R.string.podcast_details_episodes_heading), style = MaterialTheme.typography.titleMedium)
        if (feed.items.isEmpty()) {
            Text(
                text = stringResource(R.string.podcast_details_no_episodes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                feed.items.take(MAX_EPISODES_SHOWN).forEach { item -> EpisodeRow(item) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(item: ParsedFeedItem) {
    Column {
        Text(item.title, style = MaterialTheme.typography.bodyLarge)
        val dateText = EpisodeDateFormatter.format(item.publishDate?.toEpochMilli())
        if (dateText.isNotBlank()) {
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.description.isNotBlank()) {
            Text(
                text = plainText(item.description),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
