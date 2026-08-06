package com.bugzapperlabs.myfeeds.podcastdetails

import com.bugzapperlabs.myfeeds.data.feed.ParsedFeed

/** Preview of a podcast fetched on tap from a search result (issue #300) -- distinct from
 *  [com.bugzapperlabs.myfeeds.addfeed.AddFeedUiState], which tracks the subscribe action itself. */
sealed interface PodcastPreviewState {
    data object Loading : PodcastPreviewState
    data class Loaded(val feed: ParsedFeed, val resolvedUrl: String) : PodcastPreviewState
    data class Error(val message: String) : PodcastPreviewState
}
