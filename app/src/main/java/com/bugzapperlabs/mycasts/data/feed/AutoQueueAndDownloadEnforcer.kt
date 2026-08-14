package com.bugzapperlabs.mycasts.data.feed

import com.bugzapperlabs.mycasts.data.local.AutoQueuePosition
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository
import javax.inject.Inject

/**
 * Applies auto-download (`autoDownloadEnabled`, issue #23) and auto-queue (`autoQueueEnabled`,
 * issue #68) to a batch of [FeedUpdateResult]s. Extracted out of [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorker]
 * (issue #88) so manual pull-to-refresh -- both `FeedListViewModel.refresh()` and
 * `EpisodeListViewModel.refresh()` -- can trigger the same behavior the background worker does,
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
                val podcastEpisodes = success.newItemIds.mapNotNull { feedRepository.getItem(it) }.filter { it.isPodcastEpisode }
                // Only episodes newer than what's already auto-downloaded for this feed are
                // candidates (issue #162) -- otherwise a batch of "new" items that happens to
                // include something older than an existing download (a backfilled bonus episode,
                // a reissued itemGuid) would get downloaded just because there was cap headroom,
                // reaching back into the catalog instead of only ever moving forward in time.
                // Manually-downloaded episodes don't count toward this threshold, matching
                // enforceFeedDownloadCap below, which likewise only ever evicts autoDownloaded
                // ones -- a manual download shouldn't silently block a newer episode's auto-download.
                val newestAlreadyDownloaded = feed.maxDownloadsToKeep?.let {
                    feedRepository.autoDownloadedItemsForFeed(feed.id).maxOfOrNull { item -> item.publishDate ?: 0L }
                }
                val eligibleEpisodes = if (newestAlreadyDownloaded == null) {
                    podcastEpisodes
                } else {
                    podcastEpisodes.filter { (it.publishDate ?: 0L) > newestAlreadyDownloaded }
                }
                // Caps what actually gets *downloaded*, rather than starting a download for every
                // new episode and trimming back down afterward via enforceFeedDownloadCap -- a
                // feed's first fetch can bring in hundreds/thousands of "new" episodes at once
                // (same issue #102 scenario the auto-queue branch below already guards against),
                // and starting that many downloads at once enqueues that many concurrent
                // EnclosureDownloadWorkers, which was OOM-crashing the app. Newest-by-publishDate
                // wins when there are more candidates than room, same as auto-queue.
                val toDownload = feed.maxDownloadsToKeep?.let { cap ->
                    eligibleEpisodes.sortedByDescending { it.publishDate ?: 0L }.take(cap)
                } ?: eligibleEpisodes
                toDownload.forEach { item -> downloadRepository.startDownload(item, autoDownloaded = true) }
                // Makes room for what was just started above, evicting the feed's oldest
                // auto-downloaded episodes beyond the cap (issue #162) -- also re-caught-up as
                // each of these downloads actually completes, see EnclosureDownloadRepository.completeDownload.
                feed.maxDownloadsToKeep?.let { downloadRepository.enforceFeedDownloadCap(feed.id, it) }
            }

            if (feed.autoQueueEnabled) {
                val podcastEpisodes = success.newItemIds.mapNotNull { feedRepository.getItem(it) }.filter { it.isPodcastEpisode }
                // Caps what actually gets *added* to autoQueueMaxCount, rather than adding every
                // new episode and trimming back down afterward via enforceFeedCap (issue #102's
                // queue-side sibling bug) -- a feed's first fetch can bring in hundreds/thousands
                // of "new" episodes at once (e.g. via OPML import), and each addToFront/addToEnd
                // call is a real DB write, so adding all of them just to immediately delete most
                // back out is both slow and leaves Next Up wildly over-cap for as long as that
                // takes. Newest-by-publishDate wins when there are more candidates than room.
                val toQueue = feed.autoQueueMaxCount?.let { cap ->
                    podcastEpisodes.sortedByDescending { it.publishDate ?: 0L }.take(cap)
                } ?: podcastEpisodes
                toQueue.forEach { item ->
                    // issue #166: user chooses per-feed whether new episodes land at the top or
                    // bottom of Next Up.
                    when (feed.autoQueuePosition) {
                        AutoQueuePosition.TOP -> queueRepository.addToFront(item.id, autoQueued = true)
                        AutoQueuePosition.BOTTOM -> queueRepository.addToEnd(item.id, autoQueued = true)
                    }
                }
                // Still enforced afterward as a safety net -- e.g. the feed already had queued
                // episodes from a previous refresh before autoQueueMaxCount was lowered.
                feed.autoQueueMaxCount?.let { queueRepository.enforceFeedCap(feed.id, it) }
            }
        }
    }
}
