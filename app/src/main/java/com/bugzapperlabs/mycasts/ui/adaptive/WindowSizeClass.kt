package com.bugzapperlabs.mycasts.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * Whether the current window is wide enough to show a [ListDetailPaneHost] pane pair instead of
 * today's full-screen single-pane phone layout (issue #232) -- gated on width alone (Medium or
 * Expanded), not orientation, so it also covers unfolded foldables and split-screen/multi-window,
 * not just landscape tablets specifically.
 *
 * Sourced from `androidx.compose.material3.adaptive`'s [currentWindowAdaptiveInfo] (already a
 * dependency for [ListDetailPaneHost] itself) rather than the separate
 * `material3-window-size-class` artifact, so no additional dependency is needed just for this
 * check.
 */
@Composable
fun isExpandedWindowWidth(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
