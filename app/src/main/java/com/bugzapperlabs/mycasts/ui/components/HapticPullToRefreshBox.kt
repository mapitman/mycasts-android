package com.bugzapperlabs.mycasts.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.bugzapperlabs.mycasts.ui.haptics.HapticEvent
import com.bugzapperlabs.mycasts.ui.haptics.rememberHaptics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Fires the pull-to-refresh threshold haptic for a screen that drives [state] itself (issue #292). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullThresholdHaptics(state: PullToRefreshState, isRefreshing: Boolean) {
    val haptics = rememberHaptics()
    val refreshing by rememberUpdatedState(isRefreshing)
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }
            .distinctUntilChanged()
            .drop(1)
            .collect { if (!refreshing) haptics.perform(HapticEvent.GestureThreshold) }
    }
}

/**
 * [PullToRefreshBox] that gives one haptic tick each time the pull crosses the refresh threshold
 * (issue #292), and again if the user pulls back under it. Nothing fires while a refresh is
 * running, so a finishing refresh (a passive event) stays silent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullThresholdHaptics(state, isRefreshing)
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        content = content,
    )
}
