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
    fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean)
    fun cancelDownload(itemId: String)
    fun cancelAllDownloads()
    fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>>

    /** The reason code (see [EnclosureDownloadWorker.KEY_FAILURE_REASON]) of [itemId]'s most
     *  recent download job if it ended in a permanent failure that recorded one, or null
     *  otherwise -- issue #209, so a caller waiting on a just-enqueued download can tell the user
     *  something more specific than a generic timeout once it fails outright. */
    fun observeFailureReason(itemId: String): Flow<String?>
}

/**
 * Enqueues [EnclosureDownloadWorker] as unique one-off work per item (issue #23). Constraints are
 * read fresh from settings at enqueue time rather than kept in sync like the periodic refresh
 * worker's interval -- a one-off download that's already running doesn't need to be rescheduled
 * just because a setting changed after it started.
 *
 * Uses [ExistingWorkPolicy.REPLACE], not [ExistingWorkPolicy.KEEP] (issue #195): every real call
 * site only ever calls this for an item the UI itself believes isn't already downloading/downloaded
 * (see e.g. [com.bugzapperlabs.mycasts.queue.QueueViewModel.downloadAll]'s eligibility filter), so
 * a second enqueue for the same itemId only ever happens when the user is explicitly (re)starting a
 * download the UI has no other way to represent as "in progress" -- most notably a job stuck
 * endlessly retrying without ever having persisted a byte. KEEP silently no-opped in that case,
 * since a same-named unique work item already existed, leaving the user with no way to recover a
 * wedged download short of clearing all app storage. The enclosure file on disk (if any bytes were
 * already written) survives a replace -- [EnclosureDownloadWorker] resumes from it via a Range
 * request the same way it would after any other restart.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val workManager: WorkManager,
) : DownloadScheduling {
    override fun enqueueDownload(itemId: String, allowMobileData: Boolean, allowOnBattery: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(!allowOnBattery)
            .build()

        val request = OneTimeWorkRequestBuilder<EnclosureDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(EnclosureDownloadWorker.KEY_ITEM_ID to itemId))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(itemTag(itemId))
            .build()

        workManager.enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.REPLACE, request)
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

    // Scoped via the unique work name, not WORKER_CLASS_TAG/itemTag (issue #209) -- those two
    // reach every job ever enqueued for this item, including ones from long before this specific
    // attempt, which getWorkInfosForUniqueWorkFlow doesn't: REPLACE (see enqueueDownload's own
    // doc) means the unique name's history only ever reflects the current/latest generation, so
    // there's no risk of surfacing a stale failure reason left over from an earlier attempt.
    override fun observeFailureReason(itemId: String): Flow<String?> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(itemId)).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                ?.outputData?.getString(EnclosureDownloadWorker.KEY_FAILURE_REASON)
        }

    private fun workName(itemId: String) = "download-$itemId"
    private fun itemTag(itemId: String) = "$ITEM_TAG_PREFIX$itemId"

    private companion object {
        val WORKER_CLASS_TAG: String = EnclosureDownloadWorker::class.java.name
        const val ITEM_TAG_PREFIX = "enclosure-download-item:"
    }
}
