package com.bugzapperlabs.myfeeds.feedproperties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.local.AutoQueuePosition
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPropertiesScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedPropertiesViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var showUnsubscribeConfirm by remember { mutableStateOf(false) }
    var titleField by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val urlCopiedMessage = stringResource(R.string.feed_properties_url_copied)

    LaunchedEffect(uiState.isUnsubscribed) {
        if (uiState.isUnsubscribed) onBack()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feed_properties_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = titleField ?: uiState.displayTitle,
                onValueChange = { titleField = it },
                label = { Text(stringResource(R.string.feed_properties_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { titleField?.let(viewModel::setTitle) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.feed_properties_save_title))
            }

            // issue #104: surface the underlying feed URL, since it's otherwise invisible to users
            val feedUrl = uiState.feedUrl
            if (feedUrl != null) {
                Text(
                    text = stringResource(R.string.feed_properties_url_label),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = feedUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(feedUrl))
                            coroutineScope.launch { snackbarHostState.showSnackbar(urlCopiedMessage) }
                        }
                        .padding(top = 4.dp),
                )
            }

            val useGlobalMax = uiState.itemsToKeep == null
            val onUseGlobalMaxChange: (Boolean) -> Unit = { checked ->
                viewModel.setItemsToKeep(if (checked) null else uiState.globalMaxArticles)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = useGlobalMax, onValueChange = onUseGlobalMaxChange, role = Role.Switch)
                    .padding(top = 24.dp),
            ) {
                Text(
                    text = stringResource(
                        if (uiState.isPodcastFeed) {
                            R.string.feed_properties_use_global_max_episodes
                        } else {
                            R.string.feed_properties_use_global_max
                        },
                        uiState.globalMaxArticles,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = useGlobalMax, onCheckedChange = null)
            }
            if (!useGlobalMax) {
                val itemsToKeep = uiState.itemsToKeep ?: uiState.globalMaxArticles
                Text(
                    stringResource(
                        if (uiState.isPodcastFeed) {
                            R.string.feed_properties_max_episodes_for_feed
                        } else {
                            R.string.feed_properties_max_articles_for_feed
                        },
                        itemsToKeep,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = itemsToKeep.toFloat(),
                    onValueChange = { viewModel.setItemsToKeep(it.toInt()) },
                    valueRange = 5f..100f,
                    steps = 18,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = uiState.autoDownloadEnabled,
                        onValueChange = viewModel::setAutoDownloadEnabled,
                        role = Role.Switch,
                    )
                    .padding(top = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.feed_properties_auto_download),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = uiState.autoDownloadEnabled, onCheckedChange = null)
            }
            if (uiState.autoDownloadEnabled) {
                Text(
                    text = stringResource(R.string.feed_properties_max_downloads_to_keep),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    listOf(1, 3, 5, 10, null).forEach { maxCount ->
                        FilterChip(
                            selected = uiState.maxDownloadsToKeep == maxCount,
                            onClick = { viewModel.setMaxDownloadsToKeep(maxCount) },
                            label = {
                                Text(
                                    maxCount?.toString()
                                        ?: stringResource(R.string.feed_properties_auto_queue_unlimited),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = uiState.autoQueueEnabled,
                        onValueChange = viewModel::setAutoQueueEnabled,
                        role = Role.Switch,
                    )
                    .padding(top = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.feed_properties_auto_queue),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = uiState.autoQueueEnabled, onCheckedChange = null)
            }
            if (uiState.autoQueueEnabled) {
                Text(
                    text = stringResource(R.string.feed_properties_auto_queue_max_count),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    listOf(1, 3, 5, 10, null).forEach { maxCount ->
                        FilterChip(
                            selected = uiState.autoQueueMaxCount == maxCount,
                            onClick = { viewModel.setAutoQueueMaxCount(maxCount) },
                            label = {
                                Text(
                                    maxCount?.toString()
                                        ?: stringResource(R.string.feed_properties_auto_queue_unlimited),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.feed_properties_auto_queue_position),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    listOf(
                        AutoQueuePosition.TOP to R.string.feed_properties_auto_queue_position_top,
                        AutoQueuePosition.BOTTOM to R.string.feed_properties_auto_queue_position_bottom,
                    ).forEach { (position, labelRes) ->
                        FilterChip(
                            selected = uiState.autoQueuePosition == position,
                            onClick = { viewModel.setAutoQueuePosition(position) },
                            label = { Text(stringResource(labelRes)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.feed_properties_playback_speed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                    FilterChip(
                        selected = uiState.playbackSpeed == speed,
                        onClick = { viewModel.setPlaybackSpeed(speed) },
                        label = { Text("${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Text(
                text = stringResource(R.string.feed_properties_volume_boost),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                listOf(
                    0 to R.string.feed_properties_volume_boost_off,
                    600 to R.string.feed_properties_volume_boost_low,
                    1200 to R.string.feed_properties_volume_boost_medium,
                    1800 to R.string.feed_properties_volume_boost_high,
                ).forEach { (millibels, labelRes) ->
                    FilterChip(
                        selected = uiState.volumeBoostMillibels == millibels,
                        onClick = { viewModel.setVolumeBoostMillibels(millibels) },
                        label = { Text(stringResource(labelRes)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Text(
                text = stringResource(R.string.feed_properties_start_skip),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                listOf(0, 15, 30, 45, 60).forEach { seconds ->
                    FilterChip(
                        selected = uiState.startSkipSeconds == seconds,
                        onClick = { viewModel.setStartSkipSeconds(seconds) },
                        label = {
                            Text(
                                if (seconds == 0) {
                                    stringResource(R.string.feed_properties_start_skip_off)
                                } else {
                                    stringResource(R.string.feed_properties_start_skip_seconds, seconds)
                                },
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Button(
                onClick = { showUnsubscribeConfirm = true },
                modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feed_properties_unsubscribe))
            }
        }
    }

    if (showUnsubscribeConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsubscribeConfirm = false },
            title = { Text(stringResource(R.string.feed_properties_confirm_unsubscribe_title)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.isPodcastFeed) {
                            R.string.feed_properties_confirm_unsubscribe_message_podcast
                        } else {
                            R.string.feed_properties_confirm_unsubscribe_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnsubscribeConfirm = false
                    viewModel.unsubscribe()
                }) {
                    Text(stringResource(R.string.feed_properties_unsubscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsubscribeConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
