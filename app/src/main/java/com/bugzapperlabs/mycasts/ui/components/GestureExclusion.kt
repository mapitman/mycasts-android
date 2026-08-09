package com.bugzapperlabs.mycasts.ui.components

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/**
 * Reserves this composable's bounds from the system back/forward-edge gesture (issue #302) --
 * without it, a drag on a full-width [androidx.compose.material3.Slider] that starts near either
 * screen edge gets intercepted as gesture navigation instead of moving the slider. Clears the
 * exclusion on dispose so it doesn't linger over whatever screen appears next at the same
 * position.
 */
@Composable
fun Modifier.excludeFromSystemGestures(): Modifier {
    val view = LocalView.current
    DisposableEffect(view) {
        onDispose { view.systemGestureExclusionRects = emptyList() }
    }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        view.systemGestureExclusionRects = listOf(
            Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
        )
    }
}
