package com.bugzapperlabs.mycasts.refresh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.bugzapperlabs.mycasts.MainActivity
import com.bugzapperlabs.mycasts.MyCastsApp
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateEngine
import com.bugzapperlabs.mycasts.data.feed.FeedUpdateResult
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.widget.UnreadWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Periodic background refresh (issue #22). Runs [FeedUpdateEngine.updateFeeds] for every
 * subscribed feed; a per-feed failure doesn't fail the whole run since [FeedUpdateResult] already
 * carries success/failure per feed and the next scheduled run will simply retry.
 *
 * Also (issue #68): for feeds flagged with `autoQueueEnabled`, newly ingested episodes are added
 * to the Next Up queue, then that feed's queued episodes are trimmed to `autoQueueMaxCount` (if
 * set). Adding an episode to Next Up this way (or manually, from the UI) is itself what triggers
 * its download (issue #219) -- see `QueueRepository.triggerDownload`.
 *
 * Also refreshes the home-screen widget's unread counts (issue #24) once the run completes, and
 * optionally posts a notification summarizing new items (issue #25) when the user has opted in
 * via [com.bugzapperlabs.mycasts.data.settings.AppSettings.notifyOnNewItems].
 *
 * Runs as a foreground worker (issue #16) with a silent progress notification while feeds are
 * updating, the same pattern [com.bugzapperlabs.mycasts.download.EnclosureDownloadWorker] uses
 * for downloads -- separate from the summary notification above, which only appears once the run
 * finishes and only if new items were actually found.
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
        try {
            val feeds = feedRepository.observeAllFeeds().first()
            if (feeds.isNotEmpty()) trySetForeground(createForegroundInfo(completedCount = 0, totalCount = feeds.size))
            val results = feedUpdateEngine.updateFeeds(feeds) { completedCount, totalCount ->
                trySetForeground(createForegroundInfo(completedCount, totalCount))
            }

            // Per-feed failures don't fail the whole run (see class doc), but they shouldn't be
            // silently invisible either -- at minimum, surface them in logcat for debugging (issue #27).
            results.filterIsInstance<FeedUpdateResult.Failure>().forEach { failure ->
                Log.w(TAG, "Feed refresh failed: ${failure.message}")
            }

            autoQueueAndDownloadEnforcer.apply(results)

            UnreadWidget().updateAll(applicationContext)

            // Cumulative since the app was last opened (issue #161), not just this run's own new
            // items -- several scheduled refreshes can land in a row before the user ever opens the
            // app, and the notification/podcast-list highlighting should reflect all of them, not
            // just the most recent refresh's count. See SettingsDataStore.markAppOpened for where
            // this resets.
            val newItemIds = results.filterIsInstance<FeedUpdateResult.Success>().flatMap { it.newItemIds }
            if (newItemIds.isNotEmpty()) {
                settingsDataStore.addPendingNewEpisodeIds(newItemIds)
            }
            val pendingIds = settingsDataStore.settings.first().pendingNewEpisodeIds
            if (pendingIds.isNotEmpty()) {
                // Pruned against what's actually still in the database (issue #166): a feed's
                // first fetch (or one with itemsToKeep set to unlimited) can report hundreds of
                // ids here as newly inserted, only for trimToItemsToKeep to delete most of them
                // again within that same refresh, before this pool is ever read -- an unpruned
                // raw Set.size would then wildly overstate the actual number of surviving new
                // episodes, both in the notification and (left uncorrected) forever after.
                val prunedPendingIds = feedRepository.existingItemIds(pendingIds)
                if (prunedPendingIds.size != pendingIds.size) {
                    settingsDataStore.setPendingNewEpisodeIds(prunedPendingIds)
                }
                if (newItemIds.isNotEmpty() && prunedPendingIds.isNotEmpty() && settingsDataStore.settings.first().notifyOnNewItems) {
                    notifyNewItems(prunedPendingIds.size)
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            // Genuine cancellation must keep propagating, not get reported as a success below.
            throw e
        } catch (e: Exception) {
            // issue #164: an uncaught exception here otherwise fails this whole periodic run --
            // WorkManager then leaves the entire periodic schedule permanently in a terminal
            // FAILED state, which (per FeedRefreshScheduler's own doc) nothing short of a
            // REPLACE-driven reschedule ever revives, silently killing background refresh for the
            // rest of the install. Logging and reporting success instead means this just tries
            // again on the next scheduled interval, the same tolerance already given to an
            // individual feed's own fetch failure above.
            Log.e(TAG, "Feed refresh run failed unexpectedly", e)
            Result.success()
        }
    }

    /**
     * [setForeground] can fail outright when this worker runs with no user-visible activity at
     * all (e.g. a scheduled background run on a device that hasn't exempted the app from Doze) --
     * Android 12+ restricts promoting to a foreground service from that state, and WorkManager
     * surfaces the rejection as a thrown exception from [setForeground] itself. Before this fix,
     * that exception propagated straight out of [doWork]'s try block right at the very start
     * (before any feed was ever fetched) and was swallowed by the same catch-all added for issue
     * #164 -- silently reporting the whole run a success while doing nothing. The foreground
     * notification is a nice-to-have progress indicator, not a requirement for the actual refresh
     * to work, so a failure here is now just logged and the real work continues regardless.
     */
    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed; continuing refresh without the progress notification", e)
        }
    }

    private fun createForegroundInfo(completedCount: Int, totalCount: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, MyCastsApp.FEED_REFRESH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_feed_refresh_title))
            .setContentText(applicationContext.getString(R.string.notification_feed_refresh_progress, completedCount, totalCount))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(totalCount, completedCount, false)
            .build()
        return ForegroundInfo(FEED_REFRESH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        val notification = NotificationCompat.Builder(applicationContext, MyCastsApp.NEW_ITEMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_new_episodes_title))
            .setContentText(
                applicationContext.resources.getQuantityString(
                    R.plurals.notification_new_episodes_body,
                    newItemCount,
                    newItemCount,
                ),
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(MyCastsApp.NEW_ITEMS_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val FEED_REFRESH_NOTIFICATION_ID = 2
        private const val TAG = "FeedRefreshWorker"
    }
}
