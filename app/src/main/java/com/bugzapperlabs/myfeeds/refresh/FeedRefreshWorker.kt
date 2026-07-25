package com.bugzapperlabs.myfeeds.refresh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.bugzapperlabs.myfeeds.MainActivity
import com.bugzapperlabs.myfeeds.MyFeedsApp
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateResult
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.widget.UnreadWidget
import kotlinx.coroutines.flow.first

/**
 * Periodic background refresh (issue #22). Runs [FeedUpdateEngine.updateFeeds] for every
 * subscribed feed; a per-feed failure doesn't fail the whole run since [FeedUpdateResult] already
 * carries success/failure per feed and the next scheduled run will simply retry.
 *
 * User-requested addition (issue #23): for feeds flagged with `autoDownloadEnabled`, newly
 * ingested items with an enclosure are queued for background download.
 *
 * Also (issue #68): for feeds flagged with `autoQueueEnabled`, newly ingested episodes are added
 * to the Next Up queue, then that feed's queued episodes are trimmed to `autoQueueMaxCount` (if set).
 *
 * Also refreshes the home-screen widget's unread counts (issue #24) once the run completes, and
 * optionally posts a notification summarizing new items (issue #25) when the user has opted in
 * via [com.bugzapperlabs.myfeeds.data.settings.AppSettings.notifyOnNewItems].
 */
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val autoQueueAndDownloadEnforcer: AutoQueueAndDownloadEnforcer,
    private val settingsDataStore: SettingsDataStore,
    private val feedRefreshState: FeedRefreshState,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = feedRefreshState.track {
        val feeds = feedRepository.observeAllFeeds().first()
        val results = feedUpdateEngine.updateFeeds(feeds)

        // Per-feed failures don't fail the whole run (see class doc), but they shouldn't be
        // silently invisible either -- at minimum, surface them in logcat for debugging (issue #27).
        results.filterIsInstance<FeedUpdateResult.Failure>().forEach { failure ->
            Log.w(TAG, "Feed refresh failed: ${failure.message}")
        }

        autoQueueAndDownloadEnforcer.apply(results)

        UnreadWidget().updateAll(applicationContext)

        val newItemCount = results.filterIsInstance<FeedUpdateResult.Success>().sumOf { it.newItemCount }
        if (newItemCount > 0 && settingsDataStore.settings.first().notifyOnNewItems) {
            notifyNewItems(newItemCount)
        }

        Result.success()
    }

    private fun notifyNewItems(newItemCount: Int) {
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            android.content.Intent(applicationContext, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, MyFeedsApp.NEW_ITEMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_new_items_title))
            .setContentText(
                applicationContext.resources.getQuantityString(
                    R.plurals.notification_new_items_body,
                    newItemCount,
                    newItemCount,
                ),
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NEW_ITEMS_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NEW_ITEMS_NOTIFICATION_ID = 1
        private const val TAG = "FeedRefreshWorker"
    }
}
