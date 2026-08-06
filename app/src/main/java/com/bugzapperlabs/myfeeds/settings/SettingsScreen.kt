package com.bugzapperlabs.myfeeds.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bugzapperlabs.myfeeds.BuildConfig
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.settings.AppSettings
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()
    val addDefaultFeedsMessage by viewModel.addDefaultFeedsMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* If denied, the setting stays on but no notification will be shown until granted. */ }

    LaunchedEffect(addDefaultFeedsMessage) {
        addDefaultFeedsMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeAddDefaultFeedsMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_general))
            UpdateIntervalSetting(settings, viewModel)
            SwitchRow(stringResource(R.string.settings_show_images), settings.enableImageDisplay, viewModel::setEnableImageDisplay)
            SwitchRow(
                stringResource(R.string.settings_default_to_all_articles),
                settings.defaultToAllArticleView,
                viewModel::setDefaultToAllArticleView,
            )
            SwitchRow(
                stringResource(R.string.settings_notify_on_new_items),
                settings.notifyOnNewItems,
                onCheckedChange = { enabled ->
                    viewModel.setNotifyOnNewItems(enabled)
                    if (enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            MaxArticlesSetting(settings, viewModel)
            FeedRefreshConcurrencySetting(settings, viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionHeader(stringResource(R.string.settings_section_fonts))
            FontSizeRow(stringResource(R.string.settings_article_font_size), settings.articleFontSize, viewModel::setArticleFontSize)
            FontSizeRow(stringResource(R.string.settings_article_list_font_size), settings.listFontSize, viewModel::setListFontSize)
            FontSizeRow(stringResource(R.string.settings_feed_list_font_size), settings.feedListFontSize, viewModel::setFeedListFontSize)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionHeader(stringResource(R.string.settings_section_podcasts))
            SwitchRow(
                stringResource(R.string.settings_download_on_battery),
                settings.allowPodcastDownloadOnBattery,
                viewModel::setAllowPodcastDownloadOnBattery,
            )
            SwitchRow(
                stringResource(R.string.settings_download_on_cellular),
                settings.allowPodcastDownloadOnCellular,
                viewModel::setAllowPodcastDownloadOnCellular,
            )
            SwitchRow(stringResource(R.string.settings_allow_streaming), settings.allowPodcastStreaming, viewModel::setAllowPodcastStreaming)
            SwitchRow(
                stringResource(R.string.settings_auto_delete_finished_downloads),
                settings.autoDeleteFinishedDownloads,
                viewModel::setAutoDeleteFinishedDownloads,
            )
            BatteryOptimizationSetting()

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionHeader(stringResource(R.string.settings_section_podcast_search))
            PodcastSearchSetting(settings, viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionHeader(stringResource(R.string.settings_section_actions))
            ActionsSection(viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionHeader(stringResource(R.string.settings_section_about))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun UpdateIntervalSetting(settings: AppSettings, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_update_interval, settings.updateIntervalMinutes),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row {
            listOf(15L, 30L, 60L, 120L).forEach { minutes ->
                FilterChip(
                    selected = settings.updateIntervalMinutes == minutes,
                    onClick = { viewModel.setUpdateIntervalMinutes(minutes) },
                    label = { Text(stringResource(R.string.settings_update_interval_minutes_chip, minutes)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MaxArticlesSetting(settings: AppSettings, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_max_articles_per_feed, settings.maxArticles),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = settings.maxArticles.toFloat(),
            onValueChange = { viewModel.setMaxArticles(it.toInt()) },
            valueRange = 5f..100f,
            steps = 18,
        )
    }
}

@Composable
private fun FeedRefreshConcurrencySetting(settings: AppSettings, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_feed_refresh_concurrency, settings.feedRefreshConcurrency),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = settings.feedRefreshConcurrency.toFloat(),
            onValueChange = { viewModel.setFeedRefreshConcurrency(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
        )
    }
}

/**
 * Nudges the user toward exempting the app from Doze battery optimization (issue #273): even
 * with a wake lock held across the STATE_ENDED-to-next-episode gap (issues #179, #241), Doze can
 * still independently defer/block network access for a non-exempt app in that window, which
 * intermittently broke background auto-advance despite those earlier fixes. Re-checks on resume
 * since [AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]'s system dialog returns
 * straight to this screen rather than navigating away from it.
 */
@Composable
private fun BatteryOptimizationSetting() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.settings_battery_optimization_heading), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_battery_optimization_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        if (isIgnoringOptimizations) {
            Text(
                stringResource(R.string.settings_battery_optimization_granted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.settings_battery_optimization_button))
            }
        }
    }
}

/** Free API credentials for live podcast search via podcastindex.org (issue #93) -- search
 *  silently falls back to the offline directory when either field is blank, see
 *  [com.bugzapperlabs.myfeeds.data.directory.PodcastSearchService]. Local text-field state tracks
 *  whether the user has actually edited it: until they do, it keeps re-syncing from [settings]
 *  (a [SharingStarted.WhileSubscribed] StateFlow can briefly show the empty default here on
 *  re-navigation, before the real persisted value finishes loading from DataStore -- syncing only
 *  pre-edit means that catches up instead of leaving the field stuck on the stale default). Once
 *  the user types, local edits win outright so a DataStore write round trip doesn't fight typing. */
@Composable
private fun PodcastSearchSetting(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(settings.podcastIndexApiKey.orEmpty()) }
    var apiSecret by remember { mutableStateOf(settings.podcastIndexApiSecret.orEmpty()) }
    var apiKeyEdited by remember { mutableStateOf(false) }
    var apiSecretEdited by remember { mutableStateOf(false) }

    LaunchedEffect(settings.podcastIndexApiKey) {
        if (!apiKeyEdited) apiKey = settings.podcastIndexApiKey.orEmpty()
    }
    LaunchedEffect(settings.podcastIndexApiSecret) {
        if (!apiSecretEdited) apiSecret = settings.podcastIndexApiSecret.orEmpty()
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_podcast_search_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://podcastindex.org/signup"))) },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Text(stringResource(R.string.settings_podcast_search_get_key_button))
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                apiKeyEdited = true
                viewModel.setPodcastIndexApiKey(it)
            },
            label = { Text(stringResource(R.string.settings_podcast_search_api_key_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = {
                apiSecret = it
                apiSecretEdited = true
                viewModel.setPodcastIndexApiSecret(it)
            },
            label = { Text(stringResource(R.string.settings_podcast_search_api_secret_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun FontSizeRow(label: String, selected: FontSize, onSelect: (FontSize) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(modifier = Modifier.padding(top = 4.dp)) {
            FontSize.entries.forEach { size ->
                FilterChip(
                    selected = selected == size,
                    onClick = { onSelect(size) },
                    label = { Text(size.name) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionsSection(viewModel: SettingsViewModel) {
    var confirmAction by remember { mutableStateOf<ConfirmableAction?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exportFeedsChooserTitle = stringResource(R.string.settings_export_feeds_chooser_title)

    // Storage Access Framework: the system picker itself grants write access to whatever
    // destination the user chooses (Downloads, Drive, etc.), so this needs no storage permission
    // (issue #151), unlike writing directly to shared storage.
    val saveOpmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) coroutineScope.launch { viewModel.writeOpmlTo(uri) }
    }

    Column {
        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    val file = viewModel.exportOpmlToFile()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/x-opml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, exportFeedsChooserTitle))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.settings_export_opml))
        }
        OutlinedButton(
            onClick = { saveOpmlLauncher.launch("myfeeds-export.opml") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.settings_save_opml_to_file))
        }
        OutlinedButton(onClick = { confirmAction = ConfirmableAction.ClearPodcasts }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(R.string.settings_clear_podcasts))
        }
        OutlinedButton(onClick = { confirmAction = ConfirmableAction.AddDefaultFeeds }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(R.string.settings_add_default_feeds))
        }
        Button(onClick = { confirmAction = ConfirmableAction.RemoveAllFeeds }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(R.string.settings_remove_all_feeds))
        }
        Button(onClick = { confirmAction = ConfirmableAction.Reset }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(R.string.settings_reset_settings))
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(stringResource(action.titleRes)) },
            text = { Text(stringResource(action.messageRes)) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        ConfirmableAction.ClearPodcasts -> viewModel.clearPodcasts()
                        ConfirmableAction.AddDefaultFeeds -> viewModel.addDefaultFeeds()
                        ConfirmableAction.RemoveAllFeeds -> viewModel.removeAllFeeds()
                        ConfirmableAction.Reset -> viewModel.resetSettings()
                    }
                    confirmAction = null
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private enum class ConfirmableAction(@StringRes val titleRes: Int, @StringRes val messageRes: Int) {
    ClearPodcasts(R.string.settings_confirm_clear_podcasts_title, R.string.settings_confirm_clear_podcasts_message),
    AddDefaultFeeds(R.string.settings_confirm_add_default_feeds_title, R.string.settings_confirm_add_default_feeds_message),
    RemoveAllFeeds(R.string.settings_confirm_remove_all_feeds_title, R.string.settings_confirm_remove_all_feeds_message),
    Reset(R.string.settings_confirm_reset_title, R.string.settings_confirm_reset_message),
}
