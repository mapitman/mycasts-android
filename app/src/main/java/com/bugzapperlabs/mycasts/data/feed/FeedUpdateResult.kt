package com.bugzapperlabs.mycasts.data.feed

sealed interface FeedUpdateResult {
    data class Success(
        val feedId: Long,
        val newItemIds: List<String>,
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
