package com.bugzapperlabs.mycasts.ui.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Shared tablet-landscape list+detail pane host (issue #232), used by all three list+detail
 * relationships in this app -- feed list+episode list, episode list+episode details, and
 * Queue+current episode. Only meant to be rendered on Medium/Expanded window width; callers
 * branch on window size class themselves and fall back to today's full-screen single-pane
 * behavior on compact windows -- this composable has no size-class awareness of its own.
 *
 * Built directly on [ListDetailPaneScaffold] + [rememberListDetailPaneScaffoldNavigator] rather
 * than the convenience `NavigableListDetailPaneScaffold` composable, since that wrapper (and the
 * Navigation3 integration it's built on) wasn't introduced until material3-adaptive 1.3.0, which
 * needs compileSdk 37 / AGP 9.1+ -- well beyond this project's current AGP 8.7.3/compileSdk 36.
 * The two pieces used here are both available as of 1.2.0, the newest version that still builds
 * against this project's current AGP/compileSdk.
 *
 * [listContent] is rendered directly -- it's expected to already be correctly scoped by whatever
 * outer top-level `NavHost` destination hosts this pane pair (e.g. `EpisodeListScreen` reusing
 * the outer `episodeList/{feedId}` destination's own `SavedStateHandle`-backed feedId), so it
 * needs no special wiring here.
 *
 * [detailKey] identifies what should show in the detail pane (null = nothing selected yet, list
 * pane only). [detailContent] renders it -- callers whose detail content also needs its own
 * `SavedStateHandle`-backed args (e.g. `EpisodeDetailsScreen`'s feedId/itemId) are responsible for
 * wrapping [detailContent] with whatever scoping mechanism supplies that (a small nested `NavHost`
 * is the pattern used by #261/#262), since this shared utility only tracks *which* key is
 * selected, not how a given screen resolves that key into its own view-model state.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> ListDetailPaneHost(
    listContent: @Composable () -> Unit,
    detailKey: T?,
    detailContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    emptyDetailContent: @Composable () -> Unit = {},
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<T>()
    val scope = rememberCoroutineScope()

    // Drives the navigator's pane visibility (list-only vs. list+detail) from the caller's own
    // selection state, rather than the navigator's own destination-history stack -- detailKey is
    // the single source of truth for "what's selected," set by whoever calls this (a list-pane
    // tap, or e.g. the Queue pane pair's currently-playing episode).
    LaunchedEffect(detailKey) {
        val key = detailKey
        if (key != null) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
        }
    }

    // Collapses detail->list-only first; only once the navigator has nothing left to collapse
    // does back fall through to whatever's above this composable (the top-level NavHost).
    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        modifier = modifier.fillMaxSize(),
        listPane = {
            AnimatedPane { listContent() }
        },
        detailPane = {
            AnimatedPane {
                detailKey?.let { detailContent(it) } ?: emptyDetailContent()
            }
        },
    )
}
