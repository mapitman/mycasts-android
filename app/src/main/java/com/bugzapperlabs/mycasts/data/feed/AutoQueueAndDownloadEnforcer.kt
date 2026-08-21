package com.bugzapperlabs.mycasts.data.feed

import com.bugzapperlabs.mycasts.data.local.AutoQueuePosition
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import javax.inject.Inject

/**
 * Applies auto-queue (`autoQueueEnabled`, issue #68) to a batch of [FeedUpdateResult]s. Extracted
 * out of [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorker] (issue #88) so manual pull-to-refresh
 * -- both `FeedListViewModel.refresh()` and `EpisodeListViewModel.refresh()` -- can trigger the
 * same behavior the background worker does, instead of only seeing new episodes auto-queue on the
 * next scheduled run. Downloading is no longer triggered from here (issue #219): it's now triggered
 * by [QueueRepository] itself whenever an episode is added to Next Up, whether that add comes from
 * the auto-queue calls below or from the user manually queueing an episode.
 *
 * Looks up each feed fresh from [feedRepository] by id rather than taking a `List<Feed>` from the
 * caller, since [FeedUpdateEngine.persist] can itself flip `autoQueueEnabled` on during the same
 * fetch that produced these results (issue #137: new podcast subscriptions default to auto-queue)
 * -- a caller-supplied pre-fetch snapshot would still show the old value.
 */
class AutoQueueAndDownloadEnforcer @Inject constructor(
    private val feedRepository: FeedRepository,
    private val queueRepository: QueueRepository,
) {
    suspend fun apply(results: List<FeedUpdateResult>) {
        val successes = results.filterIsInstance<FeedUpdateResult.Success>()

        successes.forEach { success ->
            val feed = feedRepository.getFeed(success.feedId) ?: return@forEach

            if (feed.autoQueueEnabled) {
                val podcastEpisodes = success.newItemIds.mapNotNull { feedRepository.getItem(it) }.filter { it.isPodcastEpisode }
                // Caps what actually gets *added* to autoQueueMaxCount, rather than adding every
                // new episode and trimming back down afterward via enforceFeedCap (issue #102's
                // queue-side sibling bug) -- a feed's first fetch can bring in hundreds/thousands
                // of "new" episodes at once (e.g. via OPML import), and each addToFront/addToEnd
                // call is a real DB write, so adding all of them just to immediately delete most
                // back out is both slow and leaves Next Up wildly over-cap for as long as that
                // takes. Newest-by-publishDate wins when there are more candidates than room.
                //
                // Still capped even when autoQueueMaxCount itself is unlimited (issue #172): queued
                // items are permanently exempt from trimToItemsToKeep's episode-count cap (see
                // FeedRepository.trimToItemsToKeep's doc), so an unbounded burst here doesn't just
                // flood Next Up -- it also makes that whole burst permanently un-trimmable from the
                // feed's own episode list, even after the user fixes their cap settings, until
                // manually cleared from the queue. See MAX_ITEMS_PER_REFRESH_WHEN_UNLIMITED's doc.
                val toQueue = podcastEpisodes
                    .sortedByDescending { it.publishDate ?: 0L }
                    .take(feed.autoQueueMaxCount ?: MAX_ITEMS_PER_REFRESH_WHEN_UNLIMITED)
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

    companion object {
        /** How many episodes a single refresh may auto-queue/auto-download when the user's own
         *  cap is unlimited (issue #172) -- bounds only a single refresh's burst, not an ongoing
         *  cap, so "unlimited" still means unlimited retention over time; see the two call sites'
         *  own docs above for why an unbounded burst is worse than just "a lot of downloads." */
        private const val MAX_ITEMS_PER_REFRESH_WHEN_UNLIMITED = 25
    }
}
