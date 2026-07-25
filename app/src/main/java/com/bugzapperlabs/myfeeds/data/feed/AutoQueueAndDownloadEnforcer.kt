package com.bugzapperlabs.myfeeds.data.feed

import com.bugzapperlabs.myfeeds.data.local.AutoQueuePosition
import com.bugzapperlabs.myfeeds.data.local.isPodcastEpisode
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.repository.QueueRepository
import com.bugzapperlabs.myfeeds.download.EnclosureDownloadRepository
import javax.inject.Inject

/**
 * Applies auto-download (`autoDownloadEnabled`, issue #23) and auto-queue (`autoQueueEnabled`,
 * issue #68) to a batch of [FeedUpdateResult]s. Extracted out of [com.bugzapperlabs.myfeeds.refresh.FeedRefreshWorker]
 * (issue #88) so manual pull-to-refresh -- both `FeedListViewModel.refresh()` and
 * `ArticleListViewModel.refresh()` -- can trigger the same behavior the background worker does,
 * instead of only seeing new episodes auto-download/auto-queue on the next scheduled run.
 *
 * Looks up each feed fresh from [feedRepository] by id rather than taking a `List<Feed>` from the
 * caller, since [FeedUpdateEngine.persist] can itself flip `autoQueueEnabled` on during the same
 * fetch that produced these results (issue #137: new podcast subscriptions default to auto-queue)
 * -- a caller-supplied pre-fetch snapshot would still show the old value.
 */
class AutoQueueAndDownloadEnforcer @Inject constructor(
    private val feedRepository: FeedRepository,
    private val downloadRepository: EnclosureDownloadRepository,
    private val queueRepository: QueueRepository,
) {
    suspend fun apply(results: List<FeedUpdateResult>) {
        val successes = results.filterIsInstance<FeedUpdateResult.Success>()

        successes.forEach { success ->
            val feed = feedRepository.getFeed(success.feedId) ?: return@forEach

            if (feed.autoDownloadEnabled) {
                success.newItemIds.forEach { itemId ->
                    val item = feedRepository.getItem(itemId) ?: return@forEach
                    if (item.isPodcastEpisode) downloadRepository.startDownload(item, autoDownloaded = true)
                }
                feed.maxDownloadsToKeep?.let { downloadRepository.enforceFeedDownloadCap(feed.id, it) }
            }

            if (feed.autoQueueEnabled) {
                success.newItemIds.forEach { itemId ->
                    val item = feedRepository.getItem(itemId) ?: return@forEach
                    if (item.isPodcastEpisode) {
                        // issue #166: user chooses per-feed whether new episodes land at the top
                        // or bottom of Next Up.
                        when (feed.autoQueuePosition) {
                            AutoQueuePosition.TOP -> queueRepository.addToFront(itemId, autoQueued = true)
                            AutoQueuePosition.BOTTOM -> queueRepository.addToEnd(itemId, autoQueued = true)
                        }
                    }
                }
                feed.autoQueueMaxCount?.let { queueRepository.enforceFeedCap(feed.id, it) }
            }
        }
    }
}
