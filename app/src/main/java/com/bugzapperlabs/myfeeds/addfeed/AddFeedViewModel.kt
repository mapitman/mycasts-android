package com.bugzapperlabs.myfeeds.addfeed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.directory.FeedDirectory
import com.bugzapperlabs.myfeeds.data.directory.FeedDirectoryEntry
import com.bugzapperlabs.myfeeds.data.feed.FeedFetchResult
import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.feed.FeedUpdateEngine
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.opml.OpmlDocument
import com.bugzapperlabs.myfeeds.data.opml.OpmlImporter
import com.bugzapperlabs.myfeeds.data.opml.OpmlParser
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AddFeedViewModel @Inject constructor(
    private val feedFetcher: FeedFetcher,
    private val feedUpdateEngine: FeedUpdateEngine,
    private val feedRepository: FeedRepository,
    private val opmlImporter: OpmlImporter,
    private val httpClient: OkHttpClient,
    private val feedDirectory: FeedDirectory,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddFeedUiState>(AddFeedUiState.Idle)
    val uiState: StateFlow<AddFeedUiState> = _uiState

    /** One-shot OPML import result for a Snackbar; cleared via [consumeOpmlImportMessage]. */
    private val _opmlImportMessage = MutableStateFlow<String?>(null)
    val opmlImportMessage: StateFlow<String?> = _opmlImportMessage

    fun consumeOpmlImportMessage() {
        _opmlImportMessage.value = null
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val searchResults: StateFlow<List<FeedDirectoryEntry>> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList()) else flowOf(query).mapLatest { feedDirectory.search(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun addFeedByUrl(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            _uiState.value = AddFeedUiState.Error(context.getString(R.string.add_feed_enter_url_error))
            return
        }

        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            addFeed(normalizeUrl(trimmedUrl))
        }
    }

    fun addFromDirectory(entry: FeedDirectoryEntry) {
        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            addFeed(entry.xmlUrl)
        }
    }

    private suspend fun addFeed(url: String) {
        when (val result = feedFetcher.fetchFeed(url)) {
            is FeedFetchResult.Failure -> _uiState.value = AddFeedUiState.Error(result.message)
            is FeedFetchResult.Success -> {
                val feedId = feedRepository.subscribe(
                    Feed(
                        title = result.feed.title,
                        feedUrl = result.resolvedUrl,
                        siteUrl = result.feed.siteUrl,
                        description = result.feed.description,
                        imageUrl = result.feed.imageUrl,
                    ),
                )
                val feed = feedRepository.getFeed(feedId)
                if (feed != null) feedUpdateEngine.updateFeed(feed)
                _uiState.value = AddFeedUiState.Success(context.getString(R.string.add_feed_added_message, result.feed.title))
            }
        }
    }

    fun importOpml(input: InputStream) {
        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            val document = withContext(Dispatchers.IO) {
                try {
                    input.use { OpmlParser.parse(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (document == null) {
                _uiState.value = AddFeedUiState.Idle
                _opmlImportMessage.value = context.getString(R.string.add_feed_invalid_opml)
            } else {
                finishImport(document)
            }
        }
    }

    fun importOpmlFromText(text: String) {
        if (text.isBlank()) {
            _opmlImportMessage.value = context.getString(R.string.add_feed_enter_opml_text_error)
            return
        }

        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            val document = withContext(Dispatchers.IO) {
                try {
                    text.byteInputStream().use { OpmlParser.parse(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (document == null) {
                _uiState.value = AddFeedUiState.Idle
                _opmlImportMessage.value = context.getString(R.string.add_feed_invalid_opml)
            } else {
                finishImport(document)
            }
        }
    }

    fun importOpmlFromUrl(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            _opmlImportMessage.value = context.getString(R.string.add_feed_enter_opml_url_error)
            return
        }

        viewModelScope.launch {
            _uiState.value = AddFeedUiState.Loading
            val document = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(normalizeUrl(trimmedUrl)).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.byteStream()?.use { OpmlParser.parse(it) }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            _uiState.value = AddFeedUiState.Idle
            if (document == null) {
                _opmlImportMessage.value = context.getString(R.string.add_feed_could_not_load_opml, trimmedUrl)
            } else {
                finishImport(document)
            }
        }
    }

    private suspend fun finishImport(document: OpmlDocument) {
        val result = opmlImporter.import(document)
        _uiState.value = AddFeedUiState.Idle
        _opmlImportMessage.value = when {
            result.importedCount > 0 -> context.getString(R.string.add_feed_imported_count, result.importedCount)
            document.feeds.isEmpty() -> context.getString(R.string.add_feed_no_feeds_found_in_opml)
            result.invalidCount > 0 -> context.getString(R.string.add_feed_some_feeds_could_not_be_imported)
            else -> context.getString(R.string.add_feed_all_feeds_already_subscribed)
        }
    }

    fun resetState() {
        _uiState.value = AddFeedUiState.Idle
    }

    private fun normalizeUrl(input: String): String =
        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            input
        } else {
            "https://$input"
        }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
