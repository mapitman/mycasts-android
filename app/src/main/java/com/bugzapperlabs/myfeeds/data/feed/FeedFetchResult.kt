package com.bugzapperlabs.myfeeds.data.feed

sealed interface FeedFetchResult {
    data class Success(val feed: ParsedFeed, val resolvedUrl: String) : FeedFetchResult
    data class Failure(val message: String) : FeedFetchResult
}
