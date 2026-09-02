package com.bugzapperlabs.mycasts.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dagger.hilt.android.AndroidEntryPoint

/**
 * Placeholder entry point (issue #276 step 3) -- real queue/now-playing screens land in step 6,
 * once the sync bridge (step 4) and watch playback service (step 5) exist to back them.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearPlaceholderScreen()
        }
    }
}

@Composable
private fun WearPlaceholderScreen() {
    MaterialTheme {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
            item { Text("MyCasts") }
        }
    }
}
