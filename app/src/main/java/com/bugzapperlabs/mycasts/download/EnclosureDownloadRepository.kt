package com.bugzapperlabs.mycasts.download

import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/** UI-facing entry point for starting/cancelling a single episode's download (issue #23). */
class EnclosureDownloadRepository @Inject constructor(
    private val feedRepository: FeedRepository,
    private val downloadScheduling: DownloadScheduling,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun startDownload(item: FeedItem, autoDownloaded: Boolean = false) {
        if (!item.isPodcastEpisode) return
        feedRepository.setAutoDownloaded(item.id, autoDownloaded)
        val settings = settingsDataStore.settings.first()
        downloadScheduling.enqueueDownload(
            itemId = item.id,
            allowMobileData = settings.allowPodcastDownloadOnMobileData,
            allowOnBattery = settings.allowPodcastDownloadOnBattery,
        )
    }

    /**
     * Marks [itemId]'s download complete and immediately re-checks its feed's max-downloads cap
     * (issue #102) -- called from [com.bugzapperlabs.mycasts.download.EnclosureDownloadWorker]
     * instead of setting [FeedItem.downloadedFilePath] directly. A whole batch of a feed's new
     * episodes can be enqueued for auto-download at once (see
     * [com.bugzapperlabs.mycasts.data.feed.AutoQueueAndDownloadEnforcer.apply]), before any of them
     * have actually finished -- [enforceFeedDownloadCap] only counts already-completed downloads,
     * so calling it right after enqueueing sees nothing to trim yet. Re-checking here, as each
     * download actually lands, catches up once completions start arriving instead of leaving every
     * enqueued download to finish regardless of [com.bugzapperlabs.mycasts.data.local.Feed.maxDownloadsToKeep].
     */
    suspend fun completeDownload(itemId: String, filePath: String) {
        feedRepository.setDownloadedFilePath(itemId, filePath)
        val item = feedRepository.getItem(itemId) ?: return
        if (!item.autoDownloaded) return
        val cap = feedRepository.getFeed(item.feedId)?.maxDownloadsToKeep ?: return
        enforceFeedDownloadCap(item.feedId, cap)
    }

    suspend fun deleteDownload(item: FeedItem) {
        downloadScheduling.cancelDownload(item.id)
        item.downloadedFilePath?.let { File(it).delete() }
        feedRepository.setDownloadedFilePath(item.id, null)
        // Also clears downloadedBytes (issue #156), not just downloadedFilePath -- a cancelled
        // in-progress download otherwise keeps whatever partial byte count it last persisted,
        // which observeDownloadedItems still treats as "has a download" (isInProgress checks
        // only downloadedFilePath == null), leaving a phantom row that looks stuck downloading
        // forever even though its worker was genuinely cancelled.
        feedRepository.setDownloadedBytes(item.id, null)
    }

    /** Cancels every in-flight/retrying download (issue #156), including ones stuck retrying
     *  indefinitely after failing before ever writing a byte -- those never show up in
     *  [com.bugzapperlabs.mycasts.data.repository.FeedRepository.observeDownloadedItems] (it
     *  filters on downloadedFilePath/downloadedBytes, both still null for them), so there's no
     *  per-item UI to cancel them individually. [observeDownloadWorkInfo] surfaces those same
     *  jobs individually instead, for cancelling one at a time.
     *
     *  Also clears downloadedBytes for every item still showing as in-progress -- cancelling the
     *  WorkManager job alone leaves that column exactly as it was, so an item that had already
     *  recorded some progress would otherwise keep showing as "in progress" forever, same as
     *  [deleteDownload] and [cancelDownload] both already have to guard against individually. */
    suspend fun cancelAllDownloads() {
        downloadScheduling.cancelAllDownloads()
        feedRepository.observeDownloadedItems().first()
            .filter { it.item.downloadedFilePath == null }
            .forEach { feedRepository.setDownloadedBytes(it.item.id, null) }
    }

    /** Every active (not yet finished) download job (issue #156), regardless of whether it's
     *  ever recorded any progress -- unlike [com.bugzapperlabs.mycasts.data.repository.FeedRepository.observeDownloadedItems],
     *  which can't see a job stuck retrying before writing a first byte. */
    fun observeDownloadWorkInfo(): Flow<List<DownloadWorkInfo>> = downloadScheduling.observeDownloadWorkInfo()

    /** See [DownloadScheduling.observeFailureReason] (issue #209). */
    fun observeFailureReason(itemId: String): Flow<String?> = downloadScheduling.observeFailureReason(itemId)

    /** Cancels a single download job by item id (issue #156), for [observeDownloadWorkInfo]'s
     *  per-row cancel -- clears any partial-progress byte count too, since a cancelled job leaves
     *  none of [deleteDownload]'s usual downloadedFilePath to signal "nothing to see here". */
    suspend fun cancelDownload(itemId: String) {
        downloadScheduling.cancelDownload(itemId)
        feedRepository.setDownloadedBytes(itemId, null)
    }

    /**
     * Deletes the oldest auto-downloaded episodes of [feedId] beyond [maxCount] (issue #250),
     * mirroring [com.bugzapperlabs.mycasts.data.repository.QueueRepository.enforceFeedCap]'s per-feed cap
     * pattern. A queued or currently-playing episode is exempt even if it's the oldest, the same
     * way [com.bugzapperlabs.mycasts.data.repository.FeedRepository.trimToItemsToKeep] protects queued
     * items from its trim -- deleting the file underneath a queued/playing episode would silently
     * pull the rug out from under it.
     */
    suspend fun enforceFeedDownloadCap(feedId: Long, maxCount: Int) {
        val downloaded = feedRepository.autoDownloadedItemsForFeed(feedId)
        val excess = downloaded.size - maxCount
        if (excess <= 0) return

        val exempt = feedRepository.queuedItemIdsForFeed(feedId).toMutableSet()
        settingsDataStore.settings.first().lastPlayingItemId?.let(exempt::add)

        downloaded.drop(maxCount).filterNot { it.id in exempt }.forEach { deleteDownload(it) }
    }
}
