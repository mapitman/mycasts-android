package com.bugzapperlabs.mycasts.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A slimmer stand-in for [androidx.compose.material3.TopAppBar] on screens that no longer show a
 * title or back arrow (issues #127/#128 follow-up): Material's TopAppBar still reserves its full
 * ~64dp component height even with both empty, stacked on top of the status-bar inset it also
 * pads for -- leaving a tall, mostly dead strip at the top of the screen. This still consumes
 * that same status-bar inset (MainActivity's outer Scaffold deliberately doesn't -- see the
 * comment on its own `contentWindowInsets`), but at whatever height [content] actually needs,
 * down to nothing at all for a screen with nothing left to show there.
 */
@Composable
fun CompactTopBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
