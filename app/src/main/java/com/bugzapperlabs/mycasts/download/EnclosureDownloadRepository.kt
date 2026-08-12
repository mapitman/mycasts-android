package com.bugzapperlabs.mycasts.download

import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
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
            allowCellular = settings.allowPodcastDownloadOnCellular,
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
