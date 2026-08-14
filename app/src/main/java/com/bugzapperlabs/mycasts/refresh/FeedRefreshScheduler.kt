package com.bugzapperlabs.mycasts.refresh

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callers (e.g. [com.bugzapperlabs.mycasts.settings.SettingsViewModel]) depend on this instead of
 * [FeedRefreshScheduler] directly so unit tests can substitute a no-op fake -- touching real
 * WorkManager from Robolectric-hosted ViewModel tests deadlocked in CI (see the scheduled-refresh
 * PR description for the reproduction).
 */
interface FeedRefreshScheduling {
    suspend fun schedule(intervalMinutes: Long)
}

/**
 * Enqueues [FeedRefreshWorker] on the interval from [com.bugzapperlabs.mycasts.data.settings.AppSettings]
 * (issue #22).
 */
@Singleton
class FeedRefreshScheduler @Inject constructor(
    private val workManager: WorkManager,
) : FeedRefreshScheduling {
    override suspend fun schedule(intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        // issue #164: ExistingPeriodicWorkPolicy.UPDATE only rewrites the work's own definition
        // (interval, constraints) -- it leaves a periodic work's state column untouched, so a job
        // already sitting in a terminal FAILED/CANCELLED state (e.g. from a single uncaught
        // exception during a run) never resumes, even across every later schedule() call this
        // install makes from here on -- not on app relaunch, not on an interval change. REPLACE
        // tears the old row down and enqueues a fresh one whenever the existing job has already
        // finished; UPDATE is still used for a healthy, still-active job so a plain interval
        // change doesn't lose its original enqueue time, per WorkManager's own guidance for that
        // case.
        val existing = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().firstOrNull()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, policyFor(existing), request)
    }

    companion object {
        const val WORK_NAME = "feed-refresh"

        /** Extracted for direct unit testing (issue #164) without touching real WorkManager,
         *  which has previously deadlocked Robolectric-hosted tests -- see the interface doc. */
        internal fun policyFor(existing: WorkInfo?): ExistingPeriodicWorkPolicy =
            if (existing != null && existing.state.isFinished) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            }
    }
}
