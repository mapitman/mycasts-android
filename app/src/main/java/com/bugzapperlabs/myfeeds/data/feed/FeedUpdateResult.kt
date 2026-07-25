package com.bugzapperlabs.myfeeds.data.feed

sealed interface FeedUpdateResult {
    data class Success(
        val feedId: Long,
        val newItemIds: List<String>,
        val evictedItemIds: List<String>,
    ) : FeedUpdateResult {
        val newItemCount: Int get() = newItemIds.size
    }

    data class Failure(val message: String) : FeedUpdateResult
}
