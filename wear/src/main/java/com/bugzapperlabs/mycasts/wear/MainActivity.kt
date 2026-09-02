package com.bugzapperlabs.mycasts.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.bugzapperlabs.mycasts.wear.nowplaying.NowPlayingScreen
import com.bugzapperlabs.mycasts.wear.queue.QueueScreen
import dagger.hilt.android.AndroidEntryPoint

private const val ROUTE_QUEUE = "queue"
private const val ROUTE_NOW_PLAYING = "nowPlaying"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

/** Two screens (issue #276): [QueueScreen] (start) and [NowPlayingScreen], reached by tapping a
 *  queued episode -- swipe-to-dismiss (standard Wear OS back gesture) returns to the queue. */
@Composable
private fun WearApp() {
    val navController = rememberSwipeDismissableNavController()
    MaterialTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_QUEUE) {
            composable(ROUTE_QUEUE) {
                QueueScreen(onEpisodeStarted = { navController.navigate(ROUTE_NOW_PLAYING) })
            }
            composable(ROUTE_NOW_PLAYING) {
                NowPlayingScreen()
            }
        }
    }
}
