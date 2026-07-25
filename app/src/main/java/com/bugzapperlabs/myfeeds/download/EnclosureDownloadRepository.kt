package com.bugzapperlabs.myfeeds.download

import com.bugzapperlabs.myfeeds.data.local.FeedItem
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
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

    suspend fun deleteDownload(item: FeedItem) {
        downloadScheduling.cancelDownload(item.id)
        item.downloadedFilePath?.let { File(it).delete() }
        feedRepository.setDownloadedFilePath(item.id, null)
    }

    /**
     * Deletes the oldest auto-downloaded episodes of [feedId] beyond [maxCount] (issue #250),
     * mirroring [com.bugzapperlabs.myfeeds.data.repository.QueueRepository.enforceFeedCap]'s per-feed cap
     * pattern. A queued or currently-playing episode is exempt even if it's the oldest, the same
     * way [com.bugzapperlabs.myfeeds.data.repository.FeedRepository.trimToItemsToKeep] protects queued
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
