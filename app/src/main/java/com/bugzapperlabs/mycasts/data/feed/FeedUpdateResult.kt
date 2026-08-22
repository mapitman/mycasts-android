package com.bugzapperlabs.mycasts.data.feed

sealed interface FeedUpdateResult {
    data class Success(
        val feedId: Long,
        val newItemIds: List<String>,
        // Subset of newItemIds published at or after the feed's previously-known newest publish
        // date (issue #238) -- what AutoQueueAndDownloadEnforcer should even consider queueing, as
        // opposed to newItemIds (used for the "N new" count and eviction protection), which stays
        // unfiltered: an old episode reappearing under a GUID the app hasn't stored before (feed
        // URL/host change, re-published episode, etc.) must not outrank "nothing" just because
        // AutoQueueAndDownloadEnforcer's own ranking only ever compared this refresh's new items
        // against each other, never against what the feed already had.
        val recentNewItemIds: List<String>,
        val evictedItemIds: List<String>,
    ) : FeedUpdateResult {
        // Excludes items evicted in this same cycle (issue #60): a feed whose upstream RSS lists
        // more episodes than itemsToKeep re-"discovers" its older, previously-trimmed-out episodes
        // as new every single refresh (trimToItemsToKeep deletes evicted rows outright, so the next
        // refresh's GUID dedup no longer finds them), immediately re-evicting them again in the same
        // pass -- inflating the new-episodes notification's count indefinitely instead of settling
        // once. newItemIds itself is left untouched since AutoQueueAndDownloadEnforcer also reads it
        // for auto-queue/auto-download, which is out of scope here.
        val newItemCount: Int get() = newItemIds.count { it !in evictedItemIds }
    }

    data class Failure(val message: String) : FeedUpdateResult
}
