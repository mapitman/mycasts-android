package com.bugzapperlabs.myfeeds.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callers depend on this instead of [DownloadManager] directly so unit tests can substitute a
 * no-op fake -- touching real WorkManager from Robolectric-hosted ViewModel tests deadlocked CI
 * (see issue #22's PR history), so nothing in this app should hold a live [WorkManager] reference
 * outside a scheduler class like this one.
 */
interface DownloadScheduling {
    fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean)
    fun cancelDownload(itemId: String)
}

/**
 * Enqueues [EnclosureDownloadWorker] as unique one-off work per item (issue #23). Constraints are
 * read fresh from settings at enqueue time rather than kept in sync like the periodic refresh
 * worker's interval -- a one-off download that's already running doesn't need to be rescheduled
 * just because a setting changed after it started.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val workManager: WorkManager,
) : DownloadScheduling {
    override fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(!allowOnBattery)
            .build()

        val request = OneTimeWorkRequestBuilder<EnclosureDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(EnclosureDownloadWorker.KEY_ITEM_ID to itemId))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelDownload(itemId: String) {
        workManager.cancelUniqueWork(workName(itemId))
    }

    private fun workName(itemId: String) = "download-$itemId"
}
