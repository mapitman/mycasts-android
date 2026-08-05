package com.bugzapperlabs.myfeeds

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import com.bugzapperlabs.myfeeds.addfeed.AddFeedScreen
import com.bugzapperlabs.myfeeds.articlelist.ArticleListScreen
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.downloads.DownloadsScreen
import com.bugzapperlabs.myfeeds.feedlist.FeedListScreen
import com.bugzapperlabs.myfeeds.feedproperties.FeedPropertiesScreen
import com.bugzapperlabs.myfeeds.feedriver.FeedRiverScreen
import com.bugzapperlabs.myfeeds.playback.MiniPlayerViewModel
import com.bugzapperlabs.myfeeds.playback.NowPlayingMiniStrip
import com.bugzapperlabs.myfeeds.playback.PlayerBottomSheetContent
import com.bugzapperlabs.myfeeds.queue.QueueViewModel
import com.bugzapperlabs.myfeeds.reader.ReaderScreen
import com.bugzapperlabs.myfeeds.refresh.FeedRefreshScheduler
import com.bugzapperlabs.myfeeds.settings.SettingsScreen
import com.bugzapperlabs.myfeeds.ui.theme.MyFeedsTheme
import com.bugzapperlabs.myfeeds.widget.UnreadWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Height of the player bottom sheet's collapsed/peek state (issue #195) -- tall enough for
 *  [com.bugzapperlabs.myfeeds.playback.MiniPlayerBar]'s full two-row control layout. */
private val PLAYER_SHEET_PEEK_HEIGHT = 312.dp

/** [androidx.compose.material3.BottomSheetDefaults.DragHandle] hardcodes 22dp of vertical padding
 *  around its pill -- much taller than the pill itself and not exposed as a parameter -- so this
 *  reproduces its look with a slim 6dp padding instead. */
@Composable
private fun SlimDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 14.dp)
            .size(width = 28.dp, height = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var feedRefreshScheduler: FeedRefreshScheduler

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

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
        // the same way tapping a widget feed lands on that feed's article list.
        val sharedUrl = intent.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        val startDestination = intent.getLongExtra(WIDGET_FEED_ID_EXTRA, -1L)
            .takeIf { it >= 0 }
            ?.let { feedId -> "articleList/$feedId" }
            ?: sharedUrl?.let { "addFeed?sharedUrl=${Uri.encode(it)}" }
            ?: "feedList"

        setContent {
            MyFeedsTheme {
                // Backs the mini-player <-> full-player shared-element morph (issue #112): the
                // artwork image and player container carry matching shared keys across
                // MiniPlayerBar (used both standalone and as the player sheet's sticky header,
                // issue #195) and the reader's hero image, so Compose animates bounds/position/
                // size between whichever pair is transitioning in/out at once instead of an
                // instant cut.
                SharedTransitionLayout {
                    val sharedTransitionScope = this
                    val navController = rememberNavController()
                    val miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel()
                    val queueViewModel: QueueViewModel = hiltViewModel()
                    val playbackState by miniPlayerViewModel.playbackState.collectAsState()
                    val queue by queueViewModel.queue.collectAsState()
                    LaunchedEffect(Unit) { miniPlayerViewModel.restoreLastPlayingItem() }
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    var currentReaderItemId by remember { mutableStateOf<String?>(null) }
                    // skipHiddenState=false (issue #197) adds a third, further-than-peek anchor:
                    // swiping the collapsed player down past its own resting position hides it
                    // down to just NowPlayingMiniStrip instead of only ever resting at the full
                    // MiniPlayerBar peek.
                    val scaffoldState = rememberBottomSheetScaffoldState(
                        bottomSheetState = rememberStandardBottomSheetState(skipHiddenState = false),
                    )
                    val coroutineScope = rememberCoroutineScope()

                    // One-time nudge (issue #273) toward exempting the app from Doze battery
                    // optimization: even with the wake lock PlaybackService holds across the
                    // STATE_ENDED-to-next-episode gap (issues #179, #241), Doze can still
                    // independently defer/block network access for a non-exempt app in that
                    // window, intermittently breaking background auto-advance. Triggered off
                    // actual playback starting, rather than e.g. app launch, so it's shown at a
                    // moment the exemption is obviously relevant.
                    val settings by settingsDataStore.settings.collectAsState(initial = null)
                    var showBatteryOptimizationPrompt by remember { mutableStateOf(false) }
                    LaunchedEffect(playbackState.currentItemId, settings?.batteryOptimizationPromptShown) {
                        val currentSettings = settings ?: return@LaunchedEffect
                        if (playbackState.currentItemId == null || currentSettings.batteryOptimizationPromptShown) return@LaunchedEffect
                        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            showBatteryOptimizationPrompt = true
                        }
                    }

                    // The reader screen has its own full player for the episode it's showing (issue #97),
                    // so the player sheet would be redundant there -- hide it only in that exact case, not
                    // just "some reader screen is open" (could be a different, non-playing episode). The
                    // nav route's itemId argument only reflects the episode the reader was *opened* on --
                    // HorizontalPager swipes don't renavigate -- so the on-screen item is tracked
                    // separately via ReaderScreen's onCurrentItemChange callback instead.
                    val isOnPlayingEpisodeReader = currentBackStackEntry?.destination?.route == "reader/{feedId}/{itemId}" &&
                        currentBackStackEntry?.arguments?.getLong("feedId") == playbackState.feedId &&
                        currentReaderItemId == playbackState.currentItemId

                    // Collapses the sheet back down (and out of the way, since it's then hidden below)
                    // if it happened to be left expanded when landing on that exact reader page.
                    LaunchedEffect(isOnPlayingEpisodeReader) {
                        if (isOnPlayingEpisodeReader) scaffoldState.bottomSheetState.partialExpand()
                    }

                    val onOpenCurrentEpisode: () -> Unit = {
                        val feedId = playbackState.feedId
                        val itemId = playbackState.currentItemId
                        if (feedId != null && itemId != null) navController.navigate("reader/$feedId/$itemId")
                    }
                    // Next Up (issue #106, #195): rather than a separate destination, it's the
                    // expanded state of the persistent player bottom sheet -- opened by expanding it.
                    // On the currently-playing episode's own reader page, though, the sheet's content
                    // is force-hidden below (issue #97) since that page already has its own full
                    // player -- expanding it alone left a stuck, empty peek with nothing shown (issue
                    // #248), so pop back off that page first to reveal the sheet before expanding it.
                    // Nothing to show at all (issue #264): with no current episode and an empty
                    // queue, sheetContent stays hidden and sheetPeekHeight is 0, so the sheet has no
                    // real anchors -- expanding it then crashes BottomSheetScaffold's drag math on a
                    // NaN. Just no-op instead.
                    val onQueueClick: () -> Unit = {
                        if (playbackState.currentItemId != null || queue.isNotEmpty()) {
                            if (isOnPlayingEpisodeReader) navController.popBackStack()
                            coroutineScope.launch { scaffoldState.bottomSheetState.expand() }
                        }
                    }

                    // Swiped down past peek to just NowPlayingMiniStrip (issue #197) -- the sheet
                    // itself is Hidden, so BottomSheetScaffold's innerPadding still reserves
                    // PLAYER_SHEET_PEEK_HEIGHT below the list content (its own peek height, unaware
                    // of this further collapsed state), leaving a large gap between the strip and
                    // whatever it's laid over (issue #203). Swapped for the strip's own measured
                    // height instead once it's this state.
                    val bottomSheetHidden = scaffoldState.bottomSheetState.currentValue == SheetValue.Hidden &&
                        playbackState.currentItemId != null &&
                        !isOnPlayingEpisodeReader
                    // issue #279: currentValue only flips once the drag has fully settled -- by
                    // then the medium bar has already finished sliding off-screen as part of the
                    // drag itself, so triggering the MiniPlayerBar<->NowPlayingMiniStrip crossfade
                    // off it left a visible gap where neither was showing. targetValue predicts
                    // where the drag is heading and updates mid-gesture, so gating the crossfade on
                    // it instead starts the handoff while the drag is still finishing, not after.
                    val bottomSheetCollapsing = scaffoldState.bottomSheetState.targetValue == SheetValue.Hidden &&
                        playbackState.currentItemId != null &&
                        !isOnPlayingEpisodeReader
                    var miniStripHeight by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current
                    val layoutDirection = LocalLayoutDirection.current

                    Box(modifier = Modifier.fillMaxSize()) {
                    BottomSheetScaffold(
                        scaffoldState = scaffoldState,
                        // Matches MiniPlayerBar's own surface color so its bottom-fading cover-art
                        // gradient (issue #195) blends into the sheet's background seamlessly,
                        // rather than meeting BottomSheetScaffold's default (a different tone).
                        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        sheetPeekHeight = if (playbackState.currentItemId != null && !isOnPlayingEpisodeReader) {
                            PLAYER_SHEET_PEEK_HEIGHT
                        } else {
                            0.dp
                        },
                        sheetDragHandle = if (playbackState.currentItemId != null || queue.isNotEmpty()) {
                            { SlimDragHandle() }
                        } else {
                            null
                        },
                        sheetContent = {
                            AnimatedVisibility(
                                // issue #279: also gated on !bottomSheetCollapsing so this exits in
                                // lockstep with NowPlayingMiniStrip's own AnimatedVisibility
                                // entering -- sharedBounds/sharedElement need exactly one holder of
                                // PLAYER_CONTAINER_KEY/PLAYER_ARTWORK_KEY visible at a time to
                                // animate the handoff between them (same as the reader<->mini-bar
                                // transition, issue #112); otherwise both sides read as "visible"
                                // simultaneously and the transition doesn't render correctly.
                                visible = !bottomSheetCollapsing && !isOnPlayingEpisodeReader &&
                                    (playbackState.currentItemId != null || queue.isNotEmpty()),
                                // issue #279: plain fade, not the default expand/shrink -- the
                                // shared-bounds transition already animates this container's size
                                // continuously; layering a clip-based shrink on top of that fights
                                // it and reads as a squish/glitch rather than a clean resize.
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                PlayerBottomSheetContent(
                                    playbackState = playbackState,
                                    queue = queue,
                                    onOpenCurrentEpisode = onOpenCurrentEpisode,
                                    onQueueEpisodeClick = { episode ->
                                        queueViewModel.playNow(episode)
                                        navController.navigate("reader/${episode.item.feedId}/${episode.item.id}")
                                    },
                                    onReorder = { ids, onComplete -> queueViewModel.reorder(ids, onComplete) },
                                    onRemoveFromQueue = queueViewModel::remove,
                                    onTogglePlayPause = miniPlayerViewModel::togglePlayPause,
                                    onSkipBackward = miniPlayerViewModel::skipBackward,
                                    onSkipForward = miniPlayerViewModel::skipForward,
                                    onNextChapter = miniPlayerViewModel::nextChapter,
                                    onPreviousChapter = miniPlayerViewModel::previousChapter,
                                    onSpeedChange = miniPlayerViewModel::setSpeed,
                                    onVolumeBoostChange = miniPlayerViewModel::setVolumeBoost,
                                    onStop = miniPlayerViewModel::stop,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = this,
                                )
                            }
                        },
                    ) { innerPadding ->
                        val contentPadding = if (bottomSheetHidden) {
                            PaddingValues(
                                start = innerPadding.calculateStartPadding(layoutDirection),
                                top = innerPadding.calculateTopPadding(),
                                end = innerPadding.calculateEndPadding(layoutDirection),
                                bottom = miniStripHeight,
                            )
                        } else {
                            innerPadding
                        }
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            modifier = Modifier.fillMaxSize().padding(contentPadding),
                        ) {
                            composable("feedList") {
                                FeedListScreen(
                                    onAddFeedClick = { navController.navigate("addFeed") },
                                    onFeedClick = { feedId -> navController.navigate("articleList/$feedId") },
                                    onSettingsClick = { navController.navigate("settings") },
                                    onQueueClick = onQueueClick,
                                    onFeedLongClick = { feedId -> navController.navigate("feedProperties/$feedId") },
                                    onReadAllFeedsClick = { navController.navigate("feedRiver") },
                                    onDownloadsClick = { navController.navigate("downloads") },
                                )
                            }
                            composable("downloads") {
                                DownloadsScreen(onBack = { navController.popBackStack() })
                            }
                            composable("feedRiver") {
                                FeedRiverScreen(
                                    onBack = { navController.popBackStack() },
                                    onArticleClick = { feedId, itemId -> navController.navigate("reader/$feedId/$itemId") },
                                    onQueueClick = onQueueClick,
                                )
                            }
                            composable(
                                "feedProperties/{feedId}",
                                arguments = listOf(navArgument("feedId") { type = NavType.LongType }),
                            ) {
                                FeedPropertiesScreen(onBack = { navController.popBackStack() })
                            }
                            composable("settings") {
                                SettingsScreen(onBack = { navController.popBackStack() })
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
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable(
                                "articleList/{feedId}",
                                arguments = listOf(navArgument("feedId") { type = NavType.LongType }),
                            ) { backStackEntry ->
                                val feedId = backStackEntry.arguments?.getLong("feedId") ?: 0L
                                ArticleListScreen(
                                    onBack = { navController.popBackStack() },
                                    onArticleClick = { itemId -> navController.navigate("reader/$feedId/$itemId") },
                                    onQueueClick = onQueueClick,
                                    onFeedSettingsClick = { navController.navigate("feedProperties/$feedId") },
                                )
                            }
                            composable(
                                "reader/{feedId}/{itemId}",
                                arguments = listOf(
                                    navArgument("feedId") { type = NavType.LongType },
                                    navArgument("itemId") { type = NavType.StringType },
                                ),
                                // The mini/expanded player already handles its own exit (issue #112),
                                // so the reader page itself grows up from the bottom and fades in to
                                // meet it, then shrinks back down on the way out.
                                enterTransition = { expandVertically(tween(300), expandFrom = Alignment.Bottom) + fadeIn(tween(300)) },
                                exitTransition = { fadeOut(tween(150)) },
                                popEnterTransition = { fadeIn(tween(150)) },
                                popExitTransition = { shrinkVertically(tween(300), shrinkTowards = Alignment.Bottom) + fadeOut(tween(300)) },
                            ) {
                                ReaderScreen(
                                    onBack = { navController.popBackStack() },
                                    onCurrentItemChange = { currentReaderItemId = it },
                                    onQueueClick = onQueueClick,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = this,
                                )
                            }
                        }
                    }
                    // issue #279: shares PLAYER_CONTAINER_KEY/PLAYER_ARTWORK_KEY with MiniPlayerBar
                    // (the same mechanism issue #112 uses for the mini-bar<->reader transition), so
                    // Compose animates the container's bounds and artwork continuously between the
                    // two instead of an instant swap or a plain cross-fade -- a genuine resize morph.
                    AnimatedVisibility(
                        visible = bottomSheetCollapsing,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        NowPlayingMiniStrip(
                            playbackState = playbackState,
                            onClick = { coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() } },
                            onTogglePlayPause = miniPlayerViewModel::togglePlayPause,
                            onSwipeUp = { coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() } },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = this,
                            modifier = Modifier.onGloballyPositioned {
                                miniStripHeight = with(density) { it.size.height.toDp() }
                            },
                        )
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
                    }
                }
            }
        }
    }

    companion object {
        /** Matches [com.bugzapperlabs.myfeeds.widget.FeedIdParam]'s key name -- Glance's actionStartActivity
         * puts ActionParameters into the launch Intent's extras keyed by parameter name. */
        const val WIDGET_FEED_ID_EXTRA = "feedId"
    }
}
