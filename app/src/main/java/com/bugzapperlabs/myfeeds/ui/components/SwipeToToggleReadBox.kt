package com.bugzapperlabs.myfeeds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bugzapperlabs.myfeeds.R

/**
 * Wraps a list row with swipe-left/right to toggle read state (issue #120), as a faster
 * alternative to long-press multi-select for a single item. The swipe never actually dismisses
 * the row -- [androidx.compose.material3.SwipeToDismissBoxState.confirmValueChange] always
 * returns false so it snaps back to settled after firing [onToggleRead].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToToggleReadBox(
    isRead: Boolean,
    onToggleRead: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onToggleRead()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val alignment = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.Settled -> Alignment.Center
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    imageVector = if (isRead) Icons.Filled.Refresh else Icons.Filled.Done,
                    contentDescription = stringResource(if (isRead) R.string.cd_mark_unread else R.string.cd_mark_read),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        // The row composables passed in as `content` are plain Rows with no background of their
        // own -- without an opaque Surface here, SwipeToDismissBox's backgroundContent (always
        // composed, meant to be revealed only during an active swipe) shows through permanently
        // behind every row instead of just the one being swiped.
        content = { Surface(color = MaterialTheme.colorScheme.surface) { content() } },
    )
}
