package com.bugzapperlabs.mycasts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import com.bugzapperlabs.mycasts.addfeed.AddFeedScreen
import com.bugzapperlabs.mycasts.podcastdetails.PodcastDetailsScreen
import com.bugzapperlabs.mycasts.episodelist.EpisodeListScreen
import com.bugzapperlabs.mycasts.data.opml.OpmlDocument
import com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator
import com.bugzapperlabs.mycasts.data.opml.OpmlParser
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.DownloadFeedbackCoordinator
import com.bugzapperlabs.mycasts.downloads.DownloadsScreen
import com.bugzapperlabs.mycasts.feedlist.FeedListScreen
import com.bugzapperlabs.mycasts.feedproperties.FeedPropertiesScreen
import com.bugzapperlabs.mycasts.playback.MiniPlayerViewModel
import com.bugzapperlabs.mycasts.playback.NowPlayingMiniStrip
import com.bugzapperlabs.mycasts.queue.QueueScreen
import com.bugzapperlabs.mycasts.episodedetails.EpisodeDetailsScreen
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduler
import com.bugzapperlabs.mycasts.settings.SettingsScreen
import com.bugzapperlabs.mycasts.ui.theme.MyCastsTheme
import com.bugzapperlabs.mycasts.widget.UnreadWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The four bottom-nav destinations (issue #96) -- used for tab-selection state and as the
 *  target set for [navigateToTopLevel]'s popUpTo/saveState/restoreState behavior. */
private val TOP_LEVEL_ROUTES = setOf("feedList", "downloads", "queue", "settings")

/** Routes where the nav bar and pinned mini-player strip show. Everywhere else (episode
 *  details, feed properties, add feed, podcast details) is a detail screen pushed on top, where
 *  a nav bar showing four unrelated tabs would be more confusing than useful. The episode list
 *  is the one non-top-level route that keeps the bar (issue #146): it's still one tap away from
 *  every tab, and losing bottom-nav access every time a feed is opened previously meant using
 *  the system back button/gesture just to reach another tab. */
private val BOTTOM_NAV_ROUTES = TOP_LEVEL_ROUTES + "episodeList/{feedId}"

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var feedRefreshScheduler: FeedRefreshScheduler

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var opmlImportCoordinator: OpmlImportCoordinator

    @Inject
    lateinit var downloadFeedbackCoordinator: DownloadFeedbackCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // (Re)schedules the periodic refresh worker for the current interval on every app launch
        // (issue #22) -- kept off Application.onCreate() since that also runs for every
        // Robolectric-hosted unit test, where touching WorkManager off the simulated main thread
        // is unsafe. Interval changes made later are rescheduled directly from SettingsViewModel.
        lifecycleScope.launch {
            feedRefreshScheduler.schedule(settingsDataStore.settings.first().updateIntervalMinutes)
        }

        // Refreshes the home-screen widget's unread counts on every app launch (issue #24); the
        // other trigger is FeedRefreshWorker completing a scheduled background refresh.
        lifecycleScope.launch { UnreadWidget().updateAll(applicationContext) }

        // issue #150: sharing a URL from another app (ACTION_SEND) lands here to add it as a feed,
        // the same way tapping a widget feed lands on that feed's episode list.
        val sharedUrl = intent.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        // issue #38: sharing an .opml file (ACTION_SEND) or opening one directly (ACTION_VIEW,
        // e.g. from a file manager) imports it the same way Settings'/Add Feed's "Import OPML"
        // does -- via the same OpmlImportCoordinator, landing on the default feed list where
        // FeedListViewModel's opmlImportResult already surfaces the outcome as a snackbar.
        val opmlUri: Uri? = when {
            intent.action == Intent.ACTION_SEND && intent.type in OPML_MIME_TYPES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            intent.action == Intent.ACTION_VIEW && intent.type in OPML_MIME_TYPES -> intent.data
            else -> null
        }
        if (opmlUri != null) {
            lifecycleScope.launch {
                // A malformed/unreadable file falls back to an empty document rather than
                // crashing -- OpmlImportCoordinator already surfaces "no feeds found" for that.
                val document = try {
                    contentResolver.openInputStream(opmlUri)?.use { OpmlParser.parse(it) }
                } catch (_: Exception) {
                    null
                } ?: OpmlDocument(emptyList())
                opmlImportCoordinator.startImport(document)
            }
        }

        val startDestination = intent.getLongExtra(WIDGET_FEED_ID_EXTRA, -1L)
            .takeIf { it >= 0 }
            ?.let { feedId -> "episodeList/$feedId" }
            ?: sharedUrl?.let { "addFeed?sharedUrl=${Uri.encode(it)}" }
            ?: "feedList"

        setContent {
            // Read up-front, before MyCastsTheme itself, so the device-theme-colors setting
            // (issue #95) can decide dynamicColor -- collected again with the same key further
            // down for the battery-optimization prompt below, reusing this single subscription.
            val settings by settingsDataStore.settings.collectAsState(initial = null)
            MyCastsTheme(dynamicColor = settings?.useDeviceThemeColors ?: true) {
                val navController = rememberNavController()
                val miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel()
                val playbackState by miniPlayerViewModel.playbackState.collectAsState()
                LaunchedEffect(Unit) { miniPlayerViewModel.restoreLastPlayingItem() }
                // Surfaces here rather than on whichever screen started the download (issue
                // #84): DownloadFeedbackCoordinator's own scope outlives any one screen, since
                // the user can navigate away from the episode details page well before a
                // download either starts making progress or times out. Its own SnackbarHost
                // (below, as a direct child of the outer Box) so it's visible regardless of
                // which tab/screen is showing.
                val downloadSnackbarHostState = remember { SnackbarHostState() }
                val downloadFeedbackResult by downloadFeedbackCoordinator.result.collectAsState()
                LaunchedEffect(downloadFeedbackResult) {
                    val message = downloadFeedbackResult ?: return@LaunchedEffect
                    downloadSnackbarHostState.showSnackbar(message)
                    downloadFeedbackCoordinator.consumeResult()
                }
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route
                val coroutineScope = rememberCoroutineScope()

                // One-time nudge (issue #273) toward exempting the app from Doze battery
                // optimization: even with the wake lock PlaybackService holds across the
                // STATE_ENDED-to-next-episode gap (issues #179, #241), Doze can still
                // independently defer/block network access for a non-exempt app in that
                // window, intermittently breaking background auto-advance. Triggered off
                // actual playback starting, rather than e.g. app launch, so it's shown at a
                // moment the exemption is obviously relevant.
                var showBatteryOptimizationPrompt by remember { mutableStateOf(false) }
                LaunchedEffect(playbackState.currentItemId, settings?.batteryOptimizationPromptShown) {
                    val currentSettings = settings ?: return@LaunchedEffect
                    if (playbackState.currentItemId == null || currentSettings.batteryOptimizationPromptShown) return@LaunchedEffect
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        showBatteryOptimizationPrompt = true
                    }
                }

                // One-time proactive POST_NOTIFICATIONS request (issue #43): without this, a
                // fresh Android 13+ install only ever prompts for the permission if the user
                // happens to visit Settings and flip "Notify on new items" -- otherwise both
                // that notification and the download-progress one (issue #15) silently fail to
                // post, since Android just drops an unpermitted notification instead of
                // erroring. Requested at app launch instead, and marked shown regardless of the
                // user's answer -- mirrors batteryOptimizationPromptShown above, since Android
                // only shows the system dialog once per install and re-requesting after a
                // denial returns denied without UI anyway.
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    coroutineScope.launch { settingsDataStore.setNotificationPermissionPromptShown(true) }
                }
                LaunchedEffect(settings?.notificationPermissionPromptShown) {
                    val currentSettings = settings ?: return@LaunchedEffect
                    if (currentSettings.notificationPermissionPromptShown ||
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) {
                        return@LaunchedEffect
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        settingsDataStore.setNotificationPermissionPromptShown(true)
                    }
                }

                // Opening episode details always collapses the back stack down to
                // feedList -> episodeList/{feedId} -> episodeDetails/{feedId}/{itemId} first
                // (issue #55), regardless of how many episodes were viewed previously or which
                // screen this was opened from (episode list, Next Up queue, or the mini-player
                // below) -- otherwise each new episode opened while already deep in a
                // details/queue/mini-player loop stacks another entry on top forever, so
                // pressing back cycles through every previously-viewed episode instead of
                // landing on this one's episode list after a single press.
                val openEpisodeDetails: (Long, String) -> Unit = { feedId, itemId ->
                    navController.navigate("episodeList/$feedId") {
                        popUpTo("feedList") { inclusive = false }
                        launchSingleTop = true
                    }
                    navController.navigate("episodeDetails/$feedId/$itemId")
                }
                // Tapping the expanded player's artwork/title area on the Next Up tab (issue #96)
                // opens the currently-playing episode's own details, not a queue row's.
                val onOpenCurrentEpisode: () -> Unit = {
                    val feedId = playbackState.feedId
                    val itemId = playbackState.currentItemId
                    if (feedId != null && itemId != null) openEpisodeDetails(feedId, itemId)
                }
                // Bottom-nav tabs and every other jump to a top-level destination (issue #96)
                // go through this rather than a plain navigate() -- popUpTo the graph's start
                // destination with saveState/restoreState keeps each tab's own scroll
                // position/back stack alive across switches instead of piling up a fresh copy
                // of feedList -> downloads -> queue -> ... every time, the standard pattern for
                // a NavigationBar backed by a single flat NavHost.
                val navigateToTopLevel: (String) -> Unit = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                val onQueueClick: () -> Unit = { navigateToTopLevel("queue") }

                Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    // No topBar here -- each screen inside NavHost has its own, which already
                    // reserves the true status-bar inset for itself. Scaffold's default
                    // contentWindowInsets reserves that same top inset a *second* time (nothing
                    // here consumes it), which was doubling up with every inner screen's own
                    // TopAppBar and leaving a large blank gap above every screen's title. Only the
                    // navigation-bar side (bottom/left/right) still needs reserving here, for
                    // detail routes where the bottomBar below isn't shown to consume it itself.
                    contentWindowInsets = WindowInsets.navigationBars,
                    bottomBar = {
                        Column {
                            // Hidden on the Next Up tab itself (issue #96) -- QueueScreen's own
                            // player sheet shows the same peeked strip there, so this would
                            // otherwise be a redundant duplicate control right above it.
                            AnimatedVisibility(
                                visible = playbackState.currentItemId != null && currentRoute != "queue",
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                NowPlayingMiniStrip(
                                    playbackState = playbackState,
                                    // Tapping the strip from anywhere but the Next Up tab itself
                                    // (issue #96) should land there with the player already open,
                                    // not just peeked -- that's the whole point of tapping it.
                                    onClick = {
                                        miniPlayerViewModel.requestExpand()
                                        navigateToTopLevel("queue")
                                    },
                                    onTogglePlayPause = miniPlayerViewModel::togglePlayPause,
                                    onSkipBackward = miniPlayerViewModel::skipBackward,
                                    onSkipForward = miniPlayerViewModel::skipForward,
                                    // Only the true bottom edge (detail routes, no NavigationBar
                                    // below) needs this padding itself -- see the param's own doc.
                                    applyNavigationBarsPadding = currentRoute !in BOTTOM_NAV_ROUTES,
                                )
                            }
                            if (currentRoute in BOTTOM_NAV_ROUTES) {
                                NavigationBar {
                                    NavigationBarItem(
                                        // Highlighted from the episode list too (issue #146):
                                        // that screen is reached from -- and returned to -- the
                                        // Feeds tab, so it reads as that tab's own content.
                                        selected = currentRoute == "feedList" || currentRoute == "episodeList/{feedId}",
                                        onClick = { navigateToTopLevel("feedList") },
                                        icon = { Icon(Icons.Filled.RssFeed, contentDescription = null) },
                                        label = { Text(stringResource(R.string.feed_list_feeds_tab)) },
                                    )
                                    NavigationBarItem(
                                        selected = currentRoute == "queue",
                                        onClick = { navigateToTopLevel("queue") },
                                        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                                        label = { Text(stringResource(R.string.queue_title)) },
                                    )
                                    NavigationBarItem(
                                        selected = currentRoute == "downloads",
                                        onClick = { navigateToTopLevel("downloads") },
                                        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                        label = { Text(stringResource(R.string.downloads_title)) },
                                    )
                                    NavigationBarItem(
                                        selected = currentRoute == "settings",
                                        onClick = { navigateToTopLevel("settings") },
                                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                        label = { Text(stringResource(R.string.settings_title)) },
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    ) {
                        composable("feedList") {
                            FeedListScreen(
                                onAddFeedClick = { navController.navigate("addFeed") },
                                onFeedClick = { feedId -> navController.navigate("episodeList/$feedId") },
                            )
                        }
                        composable("downloads") {
                            DownloadsScreen()
                        }
                        composable("queue") {
                            QueueScreen(
                                onOpenEpisode = { episode -> openEpisodeDetails(episode.item.feedId, episode.item.id) },
                                onOpenCurrentEpisode = onOpenCurrentEpisode,
                                miniPlayerViewModel = miniPlayerViewModel,
                            )
                        }
                        composable(
                            "feedProperties/{feedId}",
                            arguments = listOf(navArgument("feedId") { type = NavType.LongType }),
                        ) {
                            FeedPropertiesScreen(onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen()
                        }
                        composable(
                            "addFeed?sharedUrl={sharedUrl}",
                            arguments = listOf(
                                navArgument("sharedUrl") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) { backStackEntry ->
                            AddFeedScreen(
                                initialUrl = backStackEntry.arguments?.getString("sharedUrl"),
                                onDone = { navController.popBackStack() },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onPodcastClick = { entry ->
                                    navController.navigate(
                                        "podcastDetails?feedUrl=${Uri.encode(entry.feedUrl)}&title=${Uri.encode(entry.title)}",
                                    )
                                },
                            )
                        }
                        composable(
                            "podcastDetails?feedUrl={feedUrl}&title={title}",
                            arguments = listOf(
                                navArgument("feedUrl") { type = NavType.StringType },
                                navArgument("title") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) {
                            // Subscribing pops both this screen and the add-feed search screen
                            // beneath it (issue #300) rather than leaving the user on a details
                            // page for a podcast they already left behind.
                            PodcastDetailsScreen(
                                onDone = { navController.popBackStack("feedList", inclusive = false) },
                            )
                        }
                        composable(
                            "episodeList/{feedId}",
                            arguments = listOf(navArgument("feedId") { type = NavType.LongType }),
                        ) { backStackEntry ->
                            val feedId = backStackEntry.arguments?.getLong("feedId") ?: 0L
                            EpisodeListScreen(
                                onEpisodeClick = { itemId -> openEpisodeDetails(feedId, itemId) },
                                onQueueClick = onQueueClick,
                                onFeedSettingsClick = { navController.navigate("feedProperties/$feedId") },
                            )
                        }
                        composable(
                            "episodeDetails/{feedId}/{itemId}",
                            arguments = listOf(
                                navArgument("feedId") { type = NavType.LongType },
                                navArgument("itemId") { type = NavType.StringType },
                            ),
                            // The mini/expanded player already handles its own exit (issue #112),
                            // so the episode details page itself grows up from the bottom and fades in to
                            // meet it, then shrinks back down on the way out.
                            enterTransition = { expandVertically(tween(300), expandFrom = Alignment.Bottom) + fadeIn(tween(300)) },
                            exitTransition = { fadeOut(tween(150)) },
                            popEnterTransition = { fadeIn(tween(150)) },
                            popExitTransition = { shrinkVertically(tween(300), shrinkTowards = Alignment.Bottom) + fadeOut(tween(300)) },
                        ) {
                            EpisodeDetailsScreen(
                                onQueueClick = onQueueClick,
                            )
                        }
                    }
                }
                if (showBatteryOptimizationPrompt) {
                    AlertDialog(
                        onDismissRequest = {
                            showBatteryOptimizationPrompt = false
                            coroutineScope.launch { settingsDataStore.setBatteryOptimizationPromptShown(true) }
                        },
                        title = { Text(stringResource(R.string.battery_optimization_prompt_title)) },
                        text = { Text(stringResource(R.string.battery_optimization_prompt_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showBatteryOptimizationPrompt = false
                                coroutineScope.launch { settingsDataStore.setBatteryOptimizationPromptShown(true) }
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }) {
                                Text(stringResource(R.string.battery_optimization_prompt_allow))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showBatteryOptimizationPrompt = false
                                coroutineScope.launch { settingsDataStore.setBatteryOptimizationPromptShown(true) }
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
                // A direct child of this outer Box (issue #84), not nested inside the player
                // bottom sheet, so it's visible regardless of whether that sheet is expanded,
                // peeked, or hidden -- see downloadSnackbarHostState's own doc above.
                SnackbarHost(
                    downloadSnackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Snackbar(it) }
                }
            }
        }
    }

    companion object {
        /** Matches [com.bugzapperlabs.mycasts.widget.FeedIdParam]'s key name -- Glance's actionStartActivity
         * puts ActionParameters into the launch Intent's extras keyed by parameter name. */
        const val WIDGET_FEED_ID_EXTRA = "feedId"

        /** Mime types the OPML share/open intent filters (issue #38) register for in the manifest. */
        private val OPML_MIME_TYPES = setOf("text/x-opml", "text/xml", "application/xml")
    }
}
