package com.bugzapperlabs.mycasts.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A single active (not yet finished) download job (issue #156), abstracted from
 *  [androidx.work.WorkInfo.State] so callers don't need a WorkManager dependency just to render
 *  a status list. */
enum class DownloadWorkStatus { QUEUED, RUNNING, RETRYING, BLOCKED }

data class DownloadWorkInfo(val itemId: String, val status: DownloadWorkStatus)

/**
 * Callers depend on this instead of [DownloadManager] directly so unit tests can substitute a
 * no-op fake -- touching real WorkManager from Robolectric-hosted ViewModel tests deadlocked CI
 * (see issue #22's PR history), so nothing in this app should hold a live [WorkManager] reference
 * outside a scheduler class like this one.
 */
interface DownloadScheduling {
    fun enqueueDownload(itemId: String, allowCellular: Boolean, allowOnBattery: Boolean)
    fun cancelDownload(itemId: String)
    fun cancelAllDownloads()
    fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>>
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
            .addTag(itemTag(itemId))
            .build()

        workManager.enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelDownload(itemId: String) {
        workManager.cancelUniqueWork(workName(itemId))
    }

    // issue #156: WORKER_CLASS_TAG, not a custom tag, is what reaches every download job
    // regardless of which app version enqueued it -- WorkManager auto-tags every OneTimeWorkRequest
    // with its ListenableWorker's fully-qualified class name, so this has been present on every
    // EnclosureDownloadWorker job all along, unlike a tag this code only just started adding. A
    // custom tag added here would only ever catch jobs enqueued *after* this code shipped, missing
    // exactly the already-stuck jobs this exists to clear.
    override fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(WORKER_CLASS_TAG)
    }

    // issue #156: itemTag(itemId), not the unique work name, is how the itemId is recovered here
    // -- WorkInfo exposes its tags but not the unique work name it was enqueued under. A job
    // enqueued before this tag existed has no way to recover its itemId, so it can't appear in
    // this per-item list even though cancelAllDownloads (above) still reaches it via the
    // always-present worker-class tag.
    override fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> =
        workManager.getWorkInfosByTagFlow(WORKER_CLASS_TAG).map { infos ->
            infos.filter { !it.state.isFinished }.mapNotNull { info ->
                val itemId = info.tags.firstOrNull { it.startsWith(ITEM_TAG_PREFIX) }
                    ?.removePrefix(ITEM_TAG_PREFIX)
                    ?: return@mapNotNull null
                val status = when (info.state) {
                    WorkInfo.State.RUNNING -> DownloadWorkStatus.RUNNING
                    WorkInfo.State.BLOCKED -> DownloadWorkStatus.BLOCKED
                    WorkInfo.State.ENQUEUED -> {
                        if (info.runAttemptCount > 0) DownloadWorkStatus.RETRYING else DownloadWorkStatus.QUEUED
                    }
                    else -> return@mapNotNull null
                }
                DownloadWorkInfo(itemId, status)
            }
        }

    private fun workName(itemId: String) = "download-$itemId"
    private fun itemTag(itemId: String) = "$ITEM_TAG_PREFIX$itemId"

    private companion object {
        val WORKER_CLASS_TAG: String = EnclosureDownloadWorker::class.java.name
        const val ITEM_TAG_PREFIX = "enclosure-download-item:"
    }
}
