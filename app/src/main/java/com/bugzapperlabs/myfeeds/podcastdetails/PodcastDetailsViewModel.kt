package com.bugzapperlabs.myfeeds.podcastdetails

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.addfeed.AddFeedUiState
import com.bugzapperlabs.myfeeds.data.feed.FeedFetchResult
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedFetcher: FeedFetcher,
    private val feedRepository: FeedRepository,
    private val feedUpdateEngine: FeedUpdateEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val feedUrl: String = checkNotNull(savedStateHandle["feedUrl"])

    /** Search-result title (issue #300), shown in the app bar while the feed is still fetching. */
    val initialTitle: String? = savedStateHandle["title"]

    private val _previewState = MutableStateFlow<PodcastPreviewState>(PodcastPreviewState.Loading)
    val previewState: StateFlow<PodcastPreviewState> = _previewState

    private val _subscribeState = MutableStateFlow<AddFeedUiState>(AddFeedUiState.Idle)
    val subscribeState: StateFlow<AddFeedUiState> = _subscribeState

    init {
        viewModelScope.launch {
            _previewState.value = when (val result = feedFetcher.fetchFeed(feedUrl)) {
                is FeedFetchResult.Success -> PodcastPreviewState.Loaded(result.feed, result.resolvedUrl)
                is FeedFetchResult.Failure -> PodcastPreviewState.Error(result.message)
            }
        }
    }

    fun subscribe() {
        val loaded = _previewState.value as? PodcastPreviewState.Loaded ?: return
        viewModelScope.launch {
            _subscribeState.value = AddFeedUiState.Loading
            val feedId = feedRepository.subscribe(
                Feed(
                    title = loaded.feed.title,
                    feedUrl = loaded.resolvedUrl,
                    siteUrl = loaded.feed.siteUrl,
                    description = loaded.feed.description,
                    imageUrl = loaded.feed.imageUrl,
                ),
            )
            val feed = feedRepository.getFeed(feedId)
            if (feed != null) feedUpdateEngine.updateFeed(feed)
            _subscribeState.value = AddFeedUiState.Success(context.getString(R.string.add_feed_added_message, loaded.feed.title))
        }
    }
}
