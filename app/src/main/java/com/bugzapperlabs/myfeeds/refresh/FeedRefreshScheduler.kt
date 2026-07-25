package com.bugzapperlabs.myfeeds.refresh

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callers (e.g. [com.bugzapperlabs.myfeeds.settings.SettingsViewModel]) depend on this instead of
 * [FeedRefreshScheduler] directly so unit tests can substitute a no-op fake -- touching real
 * WorkManager from Robolectric-hosted ViewModel tests deadlocked in CI (see the scheduled-refresh
 * PR description for the reproduction).
 */
interface FeedRefreshScheduling {
    fun schedule(intervalMinutes: Long)
}

/**
 * Enqueues [FeedRefreshWorker] on the interval from [com.bugzapperlabs.myfeeds.data.settings.AppSettings]
 * (issue #22). [ExistingPeriodicWorkPolicy.UPDATE] lets rescheduling with a new interval take
 * effect without losing the original enqueue time, per WorkManager's guidance for user-driven
 * interval changes.
 */
@Singleton
class FeedRefreshScheduler @Inject constructor(
    private val workManager: WorkManager,
) : FeedRefreshScheduling {
    override fun schedule(intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "feed-refresh"
    }
}
