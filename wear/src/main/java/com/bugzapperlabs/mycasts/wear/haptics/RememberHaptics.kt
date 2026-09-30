package com.bugzapperlabs.mycasts.wear.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.bugzapperlabs.mycasts.ui.haptics.Haptics

// Lives beside the UI (issue #294) because :core has no Compose dependency.
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
